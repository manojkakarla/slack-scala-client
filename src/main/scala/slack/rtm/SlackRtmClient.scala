package slack.rtm

import java.util.concurrent.atomic.AtomicLong

import akka.actor._
import akka.pattern.ask
import akka.util.{ByteString, Timeout}
import play.api.libs.json._
import slack.api._
import slack.models._
import slack.rtm.SlackRtmConnectionActor._
import slack.rtm.WebSocketClientActor._
import spray.can.websocket.frame._

import scala.collection.mutable.{Set => MSet}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

trait RtmFailureHandler {
  def connectionFailed(state: RtmState, status: RtmConnectionStatus): Future[String]
}

case class RtmConnectionStatus(active: Boolean, connectedAt: Option[Long], failures: Int, failedAt: Seq[Long])

object SlackRtmClient {
  def apply(token: String, duration: FiniteDuration = 5.seconds)(implicit arf: ActorRefFactory): SlackRtmClient = {
    new SlackRtmClient(token, duration)
  }
}

class SlackRtmClient(token: String, duration: FiniteDuration = 5.seconds)(implicit arf: ActorRefFactory) {
  implicit val timeout = new Timeout(duration)
  implicit val ec = arf.dispatcher

  private val apiClient = BlockingSlackApiClient(token, duration)
  val state = RtmState(apiClient.startRealTimeMessageSession())
  private val actor = SlackRtmConnectionActor(token, state, duration)

  def onEvent(f: (SlackEvent) => Unit): ActorRef = {
    val handler = EventHandlerActor(f)
    addEventListener(handler)
    handler
  }

  def onMessage(f: (Message) => Unit): ActorRef = {
    val handler = MessageHandlerActor(f)
    addEventListener(handler)
    handler
  }

  def sendMessage(channelId: String, text: String, thread_ts: Option[String] = None): Future[Long] = {
    (actor ? SendMessage(channelId, text, thread_ts)).mapTo[Long]
  }

  def editMessage(channelId: String, ts: String, text: String) {
    actor ! BotEditMessage(channelId, ts, text)
  }

  def indicateTyping(channel: String) {
    actor ! TypingMessage(channel)
  }

  def addEventListener(listener: ActorRef) {
    actor ! AddEventListener(listener)
  }

  def removeEventListener(listener: ActorRef) {
    actor ! RemoveEventListener(listener)
  }

  def addErrorHandler(handler: RtmFailureHandler) {
    actor ! AddFailureHandler(handler)
  }

  def getStatus(): Future[RtmConnectionStatus] = {
    (actor ? StatusRequest()).mapTo[RtmConnectionStatus]
  }


  def getState(): RtmState = {
    state
  }

  def close() {
    arf.stop(actor)
  }
}

private[rtm] object SlackRtmConnectionActor {

  implicit val sendMessageFmt = Json.format[MessageSend]
  implicit val botEditMessageFmt = Json.format[BotEditMessage]
  implicit val typingMessageFmt = Json.format[MessageTyping]

  case class AddEventListener(listener: ActorRef)
  case class RemoveEventListener(listener: ActorRef)
  case class AddFailureHandler(handler: RtmFailureHandler)
  case class SendMessage(channelId: String, text: String, ts_thread: Option[String] = None)
  case class BotEditMessage(channelId: String, ts: String, text: String, as_user: Boolean = true, `type`:String = "chat.update")
  case class TypingMessage(channelId: String)
  case class StateRequest()
  case class StateResponse(state: RtmState)
  case class StatusRequest()
  case object ReconnectWebSocket

  def apply(token: String, state: RtmState, duration: FiniteDuration)(implicit arf: ActorRefFactory): ActorRef = {
    arf.actorOf(Props(new SlackRtmConnectionActor(token, state, duration)))
  }
}

private[rtm] class SlackRtmConnectionActor(token: String, state: RtmState, duration: FiniteDuration) extends Actor with ActorLogging {

  implicit val ec = context.dispatcher
  val apiClient = BlockingSlackApiClient(token, duration)
  val listeners = MSet[ActorRef]()
  val idCounter = new AtomicLong(1L)
  val teamDomain = Try(state.team.domain).toOption.getOrElse("Team")
  var failureHandler: Option[RtmFailureHandler] = None

  var connectFailures = 0
  var webSocketClient: Option[ActorRef] = None
  var connectedAt: Option[Long] = None
  var failedAt: Seq[Long] = Seq.empty

  def getStatus = RtmConnectionStatus(webSocketClient.nonEmpty, connectedAt, connectFailures, failedAt)

  def receive = {
    case frame: TextFrame =>
      try {
        val payload = frame.payload.decodeString("utf8")
        val payloadJson = Json.parse(payload)
        if ((payloadJson \ "type").asOpt[String].isDefined || (payloadJson \ "reply_to").asOpt[Long].isDefined) {
          Try(payloadJson.as[SlackEvent]) match {
            case Success(event) =>
              state.update(event)
              listeners.foreach(_ ! event)
            case Failure(e) => log.error(e, s"[SlackRtmClient][$teamDomain] Error reading event: $payload")
          }
        } else {
          log.warning(s"invalid slack event : $payload")
        }
      } catch {
        case e: Exception => log.error(e, s"[SlackRtmClient][$teamDomain] Error parsing text frame")
      }
    case TypingMessage(channelId) =>
      val nextId = idCounter.getAndIncrement
      val payload = Json.stringify(Json.toJson(MessageTyping(nextId, channelId)))
      webSocketClient.get ! SendFrame(TextFrame(ByteString(payload)))
    case SendMessage(channelId, text, thread_ts) =>
      val nextId = idCounter.getAndIncrement
      val payload = Json.stringify(Json.toJson(MessageSend(nextId, channelId, text, thread_ts)))
      webSocketClient.get ! SendFrame(TextFrame(ByteString(payload)))
      sender ! nextId
    case bm: BotEditMessage =>
      val payload = Json.stringify(Json.toJson(bm))
      webSocketClient.get ! SendFrame(TextFrame(ByteString(payload)))
    case StateRequest() =>
      sender ! StateResponse(state)
     case StatusRequest() =>
      sender ! getStatus
    case AddEventListener(listener) =>
      listeners += listener
      context.watch(listener)
    case RemoveEventListener(listener) =>
      listeners -= listener
    case AddFailureHandler(handler) =>
      failureHandler = Some(handler)
    case WebSocketClientConnected =>
      log.info(s"[SlackRtmConnectionActor][$teamDomain] WebSocket Client successfully connected")
      connectFailures = 0
      connectedAt = Some(System.currentTimeMillis())
      failedAt = Seq.empty
    case WebSocketClientConnectFailed =>
      val delay = Math.pow(2.0, connectFailures.toDouble).toInt
      log.info(s"[SlackRtmConnectionActor][$teamDomain] WebSocket Client failed to connect, retrying in {} seconds", delay)
      connectFailures += 1
      webSocketClient = None
      if (connectFailures < 5)
        context.system.scheduler.scheduleOnce(delay.seconds, self, ReconnectWebSocket)
      else {
        failedAt = failedAt :+ System.currentTimeMillis()
        log.error(s"[SlackRtmConnectionActor][$teamDomain] WebSocket Client connect attempts exhausted, Restart manually")
        failureHandler.map(_.connectionFailed(state, getStatus))
      }
    case ReconnectWebSocket =>
      connectWebSocket()
    case Terminated(actor) =>
      listeners -= actor
      if (webSocketClient.isDefined && webSocketClient.get == actor) {
        log.info(s"[SlackRtmConnectionActor][$teamDomain] WebSocket Client disconnected, reconnecting")
        connectWebSocket()
      }
    case _ =>
      log.warning("doesn't match any case, skip")
  }

  def connectWebSocket() {
    log.info(s"[SlackRtmConnectionActor][$teamDomain] Starting web socket client")
    try {
      val initialRtmState = apiClient.startRealTimeMessageSession()
      state.reset(initialRtmState)
      webSocketClient = Some(WebSocketClientActor(initialRtmState.url, teamDomain, Seq(self)))
      webSocketClient.foreach(context.watch)
    } catch {
      case e: Exception =>
        log.error(e, s"Caught exception trying to connect websocket for: $teamDomain")
        self ! WebSocketClientConnectFailed
    }
  }

  override def preStart() {
    connectWebSocket()
  }

  override def postStop() {
    webSocketClient.foreach(context.stop)
  }
}

private[rtm] case class MessageSend(id: Long, channel: String, text: String, thread_ts: Option[String] = None, `type`: String = "message")
private[rtm] case class MessageTyping(id: Long, channel: String, `type`: String = "typing")
