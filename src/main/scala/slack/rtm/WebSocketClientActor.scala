package slack.rtm

import java.net.URI

import akka.Done
import akka.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.stream.scaladsl._
import akka.stream.{ActorMaterializer, OverflowStrategy}

import scala.concurrent.Future
import scala.util.{Failure, Success}

private[rtm] object WebSocketClientActor {
  case class SendWSMessage(message: Message)
  case class RegisterWebsocketListener(listener: ActorRef)
  case class DeregisterWebsocketListener(listener: ActorRef)

  case object WebSocketClientConnected
  case object WebSocketClientDisconnected
  case object WebSocketClientConnectFailed

  case class WebSocketConnectSuccess(queue: SourceQueueWithComplete[Message], closed: Future[Done])
  case object WebSocketConnectFailure
  case object WebSocketDisconnected

  def apply(url: String, domain: String)(implicit arf: ActorRefFactory): ActorRef = {
    arf.actorOf(Props(new WebSocketClientActor(url, domain: String)))
  }
}

import slack.rtm.WebSocketClientActor._

private[rtm] class WebSocketClientActor(url: String, domain: String) extends Actor with ActorLogging {
  implicit val ec = context.dispatcher
  implicit val system = context.system
  implicit val materalizer = ActorMaterializer()

  val uri = new URI(url)
  var outboundMessageQueue: Option[SourceQueueWithComplete[Message]] = None

  override def receive = {
    case m: TextMessage =>
      log.debug(s"[WebSocketClientActor][$domain] Received Text Message: {}", m)
      context.parent ! m
    case m: Message =>
      log.debug(s"[WebsocketClientActor][$domain] Received Message: {}", m)
    case SendWSMessage(m) =>
      if (outboundMessageQueue.isDefined) {
        outboundMessageQueue.get.offer(m)
      }
    case WebSocketConnectSuccess(queue, closed) =>
      outboundMessageQueue = Some(queue)
      closed.onComplete(_ => self ! WebSocketDisconnected)
      context.parent ! WebSocketClientConnected
    case WebSocketDisconnected =>
      log.info(s"[WebSocketClientActor][$domain] WebSocket disconnected.")
      context.stop(self)
    case _ =>
  }

  def connectWebSocket() {
    val messageSink: Sink[Message, Future[Done]] = {
      Sink.foreach {
        case message => self ! message
      }
    }

    val queueSource: Source[Message, SourceQueueWithComplete[Message]] = {
      Source.queue[Message](1000, OverflowStrategy.dropHead)
    }

    val flow: Flow[Message, Message, (Future[Done], SourceQueueWithComplete[Message])] =
      Flow.fromSinkAndSourceMat(messageSink, queueSource)(Keep.both)

    val (upgradeResponse, (closed, messageSourceQueue)) = Http().singleWebSocketRequest(WebSocketRequest(url), flow)

    upgradeResponse.onComplete {
      case Success(upgrade) if upgrade.response.status == StatusCodes.SwitchingProtocols =>
        log.info(s"[WebSocketClientActor][$domain] Web socket connection success")
        self ! WebSocketConnectSuccess(messageSourceQueue, closed)
      case Success(upgrade) =>
        log.info(s"[WebSocketClientActor][$domain] Web socket connection failed for: {}", upgrade.response)
        context.parent ! WebSocketClientConnectFailed
        context.stop(self)
      case Failure(err) =>
        log.info(s"[WebSocketClientActor][$domain] Web socket connection failed with error: {}", err.getMessage)
        context.parent ! WebSocketClientConnectFailed
        context.stop(self)
    }
  }

  override def preStart() {
    log.info(s"WebSocketClientActor][$domain] Connecting to RTM: {}", url)
    connectWebSocket()
  }

  override def postStop() {
    outboundMessageQueue.foreach(_.complete)
    context.parent ! WebSocketClientDisconnected
  }
}
