package slack.rtm

import slack.api._
import slack.models._
import slack.rtm.SlackRtmConnectionActor._
import slack.rtm.WebSocketClientActor._
import java.util.concurrent.atomic.AtomicLong

import scala.collection.mutable.{Set => MSet}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import akka.actor._
import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import akka.pattern.ask
import play.api.libs.json._
import akka.http.scaladsl.model.ws.TextMessage

trait RtmFailureHandler {
  def connectionFailed(state: RtmState, status: RtmConnectionStatus): Future[String]
}

case class RtmConnectionStatus(active: Boolean, connectedAt: Option[Long], failures: Int, failedAt: Seq[Long])

object SlackRtmClient {
  def apply(token: String,
            slackApiBaseUri: Uri = SlackApiClient.defaultSlackApiBaseUri,
            duration: FiniteDuration = 5.seconds)(implicit arf: ActorSystem): SlackRtmClient = {
    new SlackRtmClient(token, slackApiBaseUri, duration)
  }
}

class SlackRtmClient(token: String, slackApiBaseUri: Uri, duration: FiniteDuration)(
  implicit arf: ActorSystem
) {
  private implicit val timeout = new Timeout(duration)

  val apiClient = BlockingSlackApiClient(token, slackApiBaseUri, duration)
  val state = RtmState(apiClient.startRealTimeMessageSession())
  private val actor = createConnectionActor

  def createConnectionActor: ActorRef = {
    SlackRtmConnectionActor(apiClient, state)
  }

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

  def editMessage(channelId: String, ts: String, text: String): Unit = {
    actor ! BotEditMessage(channelId, ts, text)
  }

  def indicateTyping(channel: String): Unit = {
    actor ! TypingMessage(channel)
  }

  def addEventListener(listener: ActorRef): Unit = {
    actor ! AddEventListener(listener)
  }

  def removeEventListener(listener: ActorRef): Unit = {
    actor ! RemoveEventListener(listener)
  }

  def addErrorHandler(handler: RtmFailureHandler) {
    actor ! AddFailureHandler(handler)
  }

  def getStatus(): Future[RtmConnectionStatus] = {
    (actor ? StatusRequest).mapTo[RtmConnectionStatus]
  }


  def getState(): RtmState = {
    state
  }

  def close(): Unit = {
    arf.stop(actor)
  }
}

private[rtm] object SlackRtmConnectionActor {

  implicit val sendMessageFmt = Json.format[MessageSend]
  implicit val botEditMessageFmt = Json.format[BotEditMessage]
  implicit val typingMessageFmt = Json.format[MessageTyping]
  implicit val pingMessageFmt = Json.format[Ping]

  case class AddEventListener(listener: ActorRef)
  case class RemoveEventListener(listener: ActorRef)
  case class AddFailureHandler(handler: RtmFailureHandler)
  case class SendMessage(channelId: String, text: String, ts_thread: Option[String] = None)
  case class BotEditMessage(channelId: String,
                            ts: String,
                            text: String,
                            as_user: Boolean = true,
                            `type`: String = "chat.update")
  case class TypingMessage(channelId: String)
  case object StateRequest
  case object StatusRequest
  case class StateResponse(state: RtmState)
  case object ReconnectWebSocket
  case object SendPing

  def apply(apiClient: BlockingSlackApiClient, state: RtmState)(implicit arf: ActorRefFactory): ActorRef = {
    arf.actorOf(Props(new SlackRtmConnectionActor(apiClient, state)))
  }
}

class SlackRtmConnectionActor(apiClient: BlockingSlackApiClient, state: RtmState)
    extends Actor
    with ActorLogging {

  implicit val ec = context.dispatcher
  implicit val system = context.system
  val listeners = MSet[ActorRef]()
  val idCounter = new AtomicLong(1L)
  val teamDomain = Try(state.team.domain).toOption.getOrElse("Team")
  var failureHandler: Option[RtmFailureHandler] = None

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1.minute, loggingEnabled = true) {
      case _: Exception => SupervisorStrategy.Restart
    }

  var connectFailures = 0
  var webSocketClient: Option[ActorRef] = None
  var connectedAt: Option[Long] = None
  var failedAt: Seq[Long] = Seq.empty

  def getStatus = RtmConnectionStatus(webSocketClient.nonEmpty, connectedAt, connectFailures, failedAt)

  context.system.scheduler.schedule(1.minute, 1.minute, self, SendPing)

  def receive = {
    case message: TextMessage =>
      onTextMessageReceive(message)
    case TypingMessage(channelId) =>
      val nextId = idCounter.getAndIncrement
      val payload = Json.stringify(Json.toJson(MessageTyping(nextId, channelId)))
      webSocketClient.foreach(_ ! SendWSMessage(TextMessage(payload)))
    case SendMessage(channelId, text, ts_thread) =>
      val nextId = idCounter.getAndIncrement
      val payload = Json.stringify(Json.toJson(MessageSend(nextId, channelId, text, ts_thread)))
      webSocketClient.foreach(_ ! SendWSMessage(TextMessage(payload)))
      sender ! nextId
    case bm: BotEditMessage =>
      val payload = Json.stringify(Json.toJson(bm))
      webSocketClient.foreach(_ ! SendWSMessage(TextMessage(payload)))
    case StateRequest =>
      sender ! StateResponse(state)
     case StatusRequest =>
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
    case WebSocketClientDisconnected =>
      handleWebSocketDisconnect(sender)
    case WebSocketClientConnectFailed =>
      val delay = Math.pow(2.0, connectFailures.toDouble).toInt
      log.info(s"[SlackRtmConnectionActor][$teamDomain] WebSocket Client failed to connect, retrying in {} seconds", delay)
      connectFailures += 1
      webSocketClient = None
      if (connectFailures < 10)
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
      handleWebSocketDisconnect(actor)
    case SendPing =>
      val nextId = idCounter.getAndIncrement
      val payload = Json.stringify(Json.toJson(Ping(nextId)))
      webSocketClient.map(_ ! SendWSMessage(TextMessage(payload)))
    case x =>
      log.warning(s"$x doesn't match any case, skip")
  }

  def onTextMessageReceive(message: TextMessage) = {
    try {
      val payload = message.getStrictText
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
      case e: Exception => log.error(e, s"[SlackRtmClient][$teamDomain] Error parsing text message")
    }
  }

  def connectWebSocket(): Unit = {
    log.info(s"[SlackRtmConnectionActor][$teamDomain] Starting web socket client")
    try {
      val initialRtmState = apiClient.startRealTimeMessageSession()
      state.reset(initialRtmState)
      webSocketClient = Some(WebSocketClientActor(initialRtmState.url)(context))
      webSocketClient.foreach(context.watch)
    } catch {
      case e: Exception =>
        log.error(e, s"Caught exception trying to connect websocket for: $teamDomain")
        self ! WebSocketClientConnectFailed
    }
  }

  def handleWebSocketDisconnect(actor: ActorRef): Unit = {
    if (webSocketClient.contains(actor)) {
      log.info(s"[SlackRtmConnectionActor][$teamDomain] WebSocket Client disconnected, reconnecting")
      webSocketClient.foreach(context.stop)
      connectWebSocket()
    }
  }

  override def preStart(): Unit = {
    connectWebSocket()
  }

  override def postStop(): Unit = {
    webSocketClient.foreach(context.stop)
  }
}

private[rtm] case class MessageSend(id: Long,
                                    channel: String,
                                    text: String,
                                    thread_ts: Option[String] = None,
                                    `type`: String = "message")
private[rtm] case class MessageTyping(id: Long, channel: String, `type`: String = "typing")
private[rtm] case class Ping(id: Long, `type`: String = "ping")
