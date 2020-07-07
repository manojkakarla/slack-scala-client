package slack.api

import java.io.File
import java.nio.charset.StandardCharsets

import dispatch.{Http, Req, as, url}
import org.asynchttpclient.request.body.multipart.FilePart
import play.api.libs.json._
import slack.models._

import scala.concurrent.{ExecutionContext, Future}

object SlackApiClient {

  private[api] implicit val rtmStartStateFmt = Json.format[RtmStartState]
  private[api] implicit val accessTokenFmt = Json.format[AccessToken]
  private[api] implicit val historyChunkFmt = Json.format[HistoryChunk]
  private[api] implicit val repliesChunkFmt = Json.format[RepliesChunk]
  private[api] implicit val pagingObjectFmt = Json.format[PagingObject]
  private[api] implicit val filesResponseFmt = Json.format[FilesResponse]
  private[api] implicit val fileInfoFmt = Json.format[FileInfo]
  private[api] implicit val migrationExchangeResponseFmt = Json.format[MigrationExchangeResponse]
  private[api] implicit val reactionsResponseFmt = Json.format[ReactionsResponse]
  private[api] implicit val pinsResponseFmt = Json.format[PinsResponse]

  val apiBase = url("https://slack.com/api")

  def apply(token: String): SlackApiClient = {
    new SlackApiClient(token)
  }

  def exchangeOauthForToken(clientId: String, clientSecret: String, code: String, redirectUri: Option[String] = None)(implicit ec: ExecutionContext): Future[AccessToken] = {
    val params = Seq (
      "client_id" -> clientId,
      "client_secret" -> clientSecret,
      "code" -> code,
      "redirect_uri" -> redirectUri
    )
    val res = makeApiRequest(addQueryParams(apiBase, cleanParams(params)) / "oauth.access")
    res.map(_.as[AccessToken])
  }


  private def makeApiRequest(request: Req)(implicit ec: ExecutionContext): Future[JsValue] = {
    Http.default(request OK as.String).map { response =>
      val parsed = Json.parse(response)
      val ok = (parsed \ "ok").as[Boolean]
      if(ok) {
        parsed
      } else {
        throw ApiError((parsed \ "error").as[String], Some(parsed.toString()))
      }
    }
  }

  private def extract[T](jsFuture: Future[JsValue], field: String)(implicit ec: ExecutionContext, fmt: Format[T]): Future[T] = {
    jsFuture.map(js => (js \ field).as[T])
  }

  private def addQueryParams(request: Req, queryParams: Seq[(String,String)])(implicit ec: ExecutionContext): Req = {
    var req = request
    queryParams.foreach { case (k,v) =>
      req = req.addQueryParameter(k, v)
    }
    req
  }

  private def cleanParams(params: Seq[(String,Any)]): Seq[(String,String)] = {
    var paramList = Seq[(String,String)]()
    params.foreach {
      case (k, Some(v)) => paramList :+= (k -> v.toString)
      case (k, None) => // Nothing - Filter out none
      case (k, v) => paramList :+= (k -> v.toString)
    }
    paramList
  }

}

import slack.api.SlackApiClient._

class SlackApiClient(token: String) {

  val apiBaseWithToken = apiBase.addQueryParameter("token", token)


  /**************************/
  /***   Test Endpoints   ***/
  /**************************/

  def test()(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("api.test")
    extract[Boolean](res, "ok")
  }

  def testAuth()(implicit ec: ExecutionContext): Future[AuthIdentity] = {
    val res = makeApiMethodRequest("auth.test")
    res.map(_.as[AuthIdentity])
  }


  /***************************/
  /***  Channel Endpoints  ***/
  /***************************/

  def archiveChannel(channelId: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("channels.archive", "channel" -> channelId)
    extract[Boolean](res, "ok")
  }

  def createChannel(name: String)(implicit ec: ExecutionContext): Future[Channel] = {
    val res = makeApiMethodRequest("channels.create", "name" -> name)
    extract[Channel](res, "channel")
  }

  def getChannelHistory(channelId: String, latest: Option[String] = None, oldest: Option[String] = None,
      inclusive: Option[Int] = None, count: Option[Int] = None)(implicit ec: ExecutionContext): Future[HistoryChunk] = {
    val res = makeApiMethodRequest (
      "channels.history",
      "channel" -> channelId,
      "latest" -> latest,
      "oldest" -> oldest,
      "inclusive" -> inclusive,
      "count" -> count)
    res.map(_.as[HistoryChunk])
  }

  def getChannelInfo(channelId: String)(implicit ec: ExecutionContext): Future[Channel] = {
    val res = makeApiMethodRequest("channels.info", "channel" -> channelId)
    extract[Channel](res, "channel")
  }

  def inviteToChannel(channelId: String, userId: String)(implicit ec: ExecutionContext): Future[Channel] = {
    val res = makeApiMethodRequest("channels.invite", "channel" -> channelId, "user" -> userId)
    extract[Channel](res, "channel")
  }

  def joinChannel(channelId: String)(implicit ec: ExecutionContext): Future[Channel] = {
    val res = makeApiMethodRequest("channels.join", "channel" -> channelId)
    extract[Channel](res, "channel")
  }

  def kickFromChannel(channelId: String, userId: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("channels.kick", "channel" -> channelId, "user" -> userId)
    extract[Boolean](res, "ok")
  }

  def listChannels(excludeArchived: Int = 0)(implicit ec: ExecutionContext): Future[Seq[Channel]] = {
    val res = makeApiMethodRequest("channels.list", "exclude_archived" -> excludeArchived.toString)
    extract[Seq[Channel]](res, "channels")
  }

  def leaveChannel(channelId: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("channels.leave", "channel" -> channelId)
    extract[Boolean](res, "ok")
  }

  def markChannel(channelId: String, ts: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("channels.mark", "channel" -> channelId, "ts" -> ts)
    extract[Boolean](res, "ok")
  }

  // TODO: Lite Channel Object
  def renameChannel(channelId: String, name: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("channels.rename", "channel" -> channelId, "name" -> name)
    extract[Boolean](res, "ok")
  }

  def getChannelReplies(channelId: String, thread_ts: String)(implicit ec: ExecutionContext): Future[RepliesChunk] = {
    val res = makeApiMethodRequest ("channels.replies", "channel" -> channelId, "thread_ts" -> thread_ts)
    res.map(_.as[RepliesChunk])
  }

  def setChannelPurpose(channelId: String, purpose: String)(implicit ec: ExecutionContext): Future[String] = {
    val res = makeApiMethodRequest("channels.setPurpose", "channel" -> channelId, "purpose" -> purpose)
    extract[String](res, "purpose")
  }

  def setChannelTopic(channelId: String, topic: String)(implicit ec: ExecutionContext): Future[String] = {
    val res = makeApiMethodRequest("channels.setTopic", "channel" -> channelId, "topic" -> topic)
    extract[String](res, "topic")
  }

  def unarchiveChannel(channelId: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("channels.unarchive", "channel" -> channelId)
    extract[Boolean](res, "ok")
  }


  /**************************/
  /****  Chat Endpoints  ****/
  /**************************/

  def deleteChat(channelId: String, ts: String, asUser: Option[Boolean] = None)(implicit ec: ExecutionContext): Future[Boolean] = {
    val params = Seq("channel" -> channelId, "ts" -> ts)
    val res = makeApiMethodRequest("chat.delete", asUser.map(b => params :+ ("as_user" -> b)).getOrElse(params): _*)
    extract[Boolean](res, "ok")
  }

  def postEphemeral(channelId: String, userId: String, text: String, asUser: Option[Boolean] = None,
                    attachments: Option[Seq[Attachment]] = None, blocks: Option[Seq[Block]] = None,
                    linkNames: Option[Boolean] = None, parse: Option[String] = None)(implicit ec: ExecutionContext): Future[String] = {
    val json = Json.obj(
      "channel" -> channelId,
      "text" -> text,
      "user" -> userId
    ) ++
      JsObject(Seq(
        asUser.map("as_user" -> Json.toJson(_)),
        parse.map("parse" -> Json.toJson(_)),
        linkNames.map("link_names" -> Json.toJson(_)),
        attachments.map("attachments" -> Json.toJson(_)),
        blocks.map("blocks" -> Json.toJson(_))
      ).flatten)
    val res = makeApiJsonRequest("chat.postEphemeral", json)
    extract[String](res, "message_ts")
  }

  def postChatMessage(channelId: String, text: String, username: Option[String] = None, asUser: Option[Boolean] = None,
                      parse: Option[String] = None, linkNames: Option[String] = None, attachments: Option[Seq[Attachment]] = None,
                      blocks: Option[Seq[Block]] = None, unfurlLinks: Option[Boolean] = None, unfurlMedia: Option[Boolean] = None,
                      iconUrl: Option[String] = None, iconEmoji: Option[String] = None, replaceOriginal: Option[Boolean]= None,
                      deleteOriginal: Option[Boolean] = None, threadTs: Option[String] = None,
                      replyBroadcast: Option[Boolean] = None)(implicit ec: ExecutionContext): Future[String] = {
    val json = Json.obj(
      "channel" -> channelId,
      "text" -> text) ++
    JsObject(Seq(
      username.map("username" -> Json.toJson(_)),
      asUser.map("as_user" -> Json.toJson(_)),
      threadTs.map("thread_ts" -> Json.toJson(_)),
      parse.map("parse" -> Json.toJson(_)),
      linkNames.map("link_names" -> Json.toJson(_)),
      attachments.map("attachments" -> Json.toJson(_)),
      blocks.map("blocks" -> Json.toJson(_)),
      unfurlLinks.map("unfurl_links" -> Json.toJson(_)),
      unfurlMedia.map("unfurl_media" -> Json.toJson(_)),
      iconUrl.map("icon_url" -> Json.toJson(_)),
      iconEmoji.map("icon_emoji" -> Json.toJson(_)),
      replaceOriginal.map("replace_original" -> Json.toJson(_)),
      deleteOriginal.map("delete_original" -> Json.toJson(_)),
      replyBroadcast.map("reply_broadcast" -> Json.toJson(_))
    ).flatten)
    val res = makeApiJsonRequest("chat.postMessage", json)
    extract[String](res, "ts")
  }

  def updateChatMessage(channelId: String, ts: String, text: String,
                        attachments: Option[Seq[Attachment]] = None,
                        blocks: Option[Seq[Block]] = None,
                        parse: Option[String] = None, linkNames: Option[String] = None,
                        asUser: Option[Boolean] = None,
                        threadTs: Option[String] = None)(implicit ec: ExecutionContext): Future[UpdateResponse] = {
    val json = Json.obj(
      "channel" -> channelId, "ts" -> ts, "text" -> text) ++
      JsObject(Seq(
        attachments.map("attachments" -> Json.toJson(_)),
        blocks.map("blocks" -> Json.toJson(_)),
        parse.map("parse" -> Json.toJson(_)),
        linkNames.map("link_names" -> Json.toJson(_)),
        asUser.map("as_user" -> Json.toJson(_)),
        threadTs.map("thread_ts" -> Json.toJson(_))
      ).flatten)

    val res = makeApiJsonRequest("chat.update", json)
    res.map(_.as[UpdateResponse])
  }

  /**********************************/
  /****  Conversation Endpoints  ****/
  /**********************************/

  def archiveConversation(channelId: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("conversations.archive", "channel" -> channelId)
    extract[Boolean](res, "ok")
  }

  def closeConversation(channelId: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("conversations.close", "channel" -> channelId)
    extract[Boolean](res, "ok")
  }

  def createConversation(name: String, isPrivate: Boolean, userIds: Seq[String] = Seq.empty)(implicit ec: ExecutionContext): Future[Channel] = {
    val res = makeApiJsonRequest("conversations.create", Json.obj("name" -> name, "is_private" -> isPrivate, "user_ids" -> userIds))
    res.map(js => (js \ "channel").as[Channel])
  }

  def openConversation(channelId: Option[String],
                       users: Option[Seq[String]],
                       returnIm: Option[Boolean])(implicit ec: ExecutionContext): Future[String] = {
    val json = JsObject(Seq(
        channelId.map("channel" -> JsString(_)),
        users.map(u => "users" -> JsString(u.mkString(","))),
        returnIm.map("return_im" -> JsBoolean(_))
      ).flatten)
    val res = makeApiJsonRequest("conversations.open", json)
    res.map(js => (js \ "channel" \ "id").as[String])
  }

  def inviteToConversation(channelId: String, members: Seq[String])(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("conversations.invite", "channel" -> channelId, "users" -> members.mkString(","))
    extract[Boolean](res, "ok")
  }

  def kickFromConversation(channelId: String, user: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("conversations.kick", "channel" -> channelId, "user" -> user)
    extract[Boolean](res, "ok")
  }

  def getConversationInfo(channelId: String, includeLocale: Boolean = true, includeNumMembers: Boolean = false)(implicit ec: ExecutionContext): Future[Channel] = {
    val res = makeApiMethodRequest("conversations.info", "channel" -> channelId,
      "include_locale" -> includeLocale.toString, "include_num_members" -> includeNumMembers.toString)
    extract[Channel](res, "channel")
  }

  def getConversationMembers(channelId: String, limit: Int = 100)(implicit ec: ExecutionContext): Future[Seq[String]] = {
    val res = makeApiMethodRequest("conversations.members", "channel" -> channelId, "limit" -> limit.toString)
    extract[Seq[String]](res, "members")
  }

  def getConversationHistory(channelId: String, latest: Option[String] = None, oldest: Option[String] = None,
                      inclusive: Option[Int] = None, limit: Option[Int] = None)(implicit ec: ExecutionContext): Future[HistoryChunk] = {
    val res = makeApiMethodRequest (
      "conversations.history",
      "channel" -> channelId,
      "latest" -> latest,
      "oldest" -> oldest,
      "inclusive" -> inclusive,
      "limit" -> limit)
    res.map(_.as[HistoryChunk])
  }

  def listConversations(excludeArchived: Boolean = false, limit: Int = 100, types: Seq[ConversationType] = Seq.empty)(implicit ec: ExecutionContext): Future[Seq[Channel]] = {
    val typesStr = if(types.nonEmpty) types.map(_.conversationType).mkString(",") else "public_channel"
    val res = makeApiMethodRequest("conversations.list",
      "exclude_archived" -> excludeArchived, "limit" -> limit, "types" -> typesStr)
    res.map(js => (js \ "channels").as[Seq[Channel]])
  }

  def unarchiveConversation(channelId: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("conversations.unarchive", "channel" -> channelId)
    extract[Boolean](res, "ok")
  }

  def renameConversation(channelId: String, name: String)(implicit ec: ExecutionContext): Future[Channel] = {
    val res = makeApiMethodRequest("conversations.rename", "channel" -> channelId, "name" -> name)
    res.map(_.as[Channel])
  }

  def setConversationPurpose(channelId: String, purpose: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("conversations.setPurpose", "channel" -> channelId, "purpose" -> purpose)
    extract[Boolean](res, "ok")
  }

  def setConversationTopic(channelId: String, topic: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("conversations.setTopic", "channel" -> channelId, "topic" -> topic)
    extract[Boolean](res, "ok")
  }

  /****************************/
  /****  Dialog Endpoints  ****/
  /****************************/

  def openDialog(triggerId: String, dialog: Dialog)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiJsonRequest("dialog.open", Json.obj("trigger_id" -> triggerId, "dialog" -> Json.toJson(dialog).toString()))
    extract[Boolean](res, "ok")
  }


  /***************************/
  /****  Emoji Endpoints  ****/
  /***************************/

  def listEmojis()(implicit ec: ExecutionContext): Future[Map[String,String]] = {
    val res = makeApiMethodRequest("emoji.list")
    extract[Map[String,String]](res, "emoji")
  }


  /**************************/
  /****  File Endpoints  ****/
  /**************************/

  def deleteFile(fileId: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("files.delete", "file" -> fileId)
    extract[Boolean](res, "ok")
  }

  def getFileInfo(fileId: String, count: Option[Int] = None, page: Option[Int] = None)(implicit ec: ExecutionContext): Future[FileInfo] = {
    val res = makeApiMethodRequest("files.info", "file" -> fileId, "count" -> count, "page" -> page)
    res.map(_.as[FileInfo])
  }

  def listFiles(userId: Option[String] = None, tsFrom: Option[String] = None, tsTo: Option[String] = None, types: Option[Seq[String]] = None,
      count: Option[Int] = None, page: Option[Int] = None)(implicit ec: ExecutionContext): Future[FilesResponse] = {
    val res = makeApiMethodRequest (
      "files.list",
      "user" -> userId,
      "ts_from" -> tsFrom,
      "ts_to" -> tsTo,
      "types" -> types.map(_.mkString(",")),
      "count" -> count,
      "page" -> page)
    res.map(_.as[FilesResponse])
  }

  def uploadFile(content: Either[File, String], filetype: Option[String] = None, filename: Option[String] = None,
    title: Option[String] = None, initialComment: Option[String] = None, channels: Option[Seq[String]] = None)(implicit ex: ExecutionContext): Future[SlackFile] = {
    val params = Seq(
      "filetype" -> filetype,
      "filename" -> filename,
      "title" -> title,
      "initial_comment" -> initialComment,
      "channels" -> channels.map(_.mkString(","))
    )

    val req = apiBaseWithToken / "files.upload"
    val res = content match {
      case Right(str) =>
        makeApiRequest(addQueryParams(req, cleanParams(params ++ Seq("content" -> str))))
      case Left(file) =>
        val multi = req.setContentType("multipart/form-data", StandardCharsets.UTF_8).setHeader("Transfer-Encoding", "chunked").POST
        val withFile = multi.addBodyPart(new FilePart("file", file))
        makeApiRequest(addQueryParams(withFile, cleanParams(params)))
    }
    extract[SlackFile](res, "file")
  }


  /***************************/
  /****  Group Endpoints  ****/
  /***************************/

  def archiveGroup(channelId: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("groups.archive", "channel" -> channelId)
    extract[Boolean](res, "ok")
  }

  def closeGroup(channelId: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("groups.close", "channel" -> channelId)
    extract[Boolean](res, "ok")
  }

  def createGroup(name: String)(implicit ec: ExecutionContext): Future[Group] = {
    val res = makeApiMethodRequest("groups.create", "name" -> name)
    extract[Group](res, "group")
  }

  def createChildGroup(channelId: String)(implicit ec: ExecutionContext): Future[Group] = {
    val res = makeApiMethodRequest("groups.createChild", "channel" -> channelId)
    extract[Group](res, "group")
  }

  def getGroupHistory(channelId: String, latest: Option[String] = None, oldest: Option[String] = None,
      inclusive: Option[Int] = None, count: Option[Int] = None)(implicit ec: ExecutionContext): Future[HistoryChunk] = {
    val res = makeApiMethodRequest (
      "groups.history",
      "channel" -> channelId,
      "latest" -> latest,
      "oldest" -> oldest,
      "inclusive" -> inclusive,
      "count" -> count)
    res.map(_.as[HistoryChunk])
  }

  def getGroupInfo(channelId: String)(implicit ec: ExecutionContext): Future[Group] = {
    val res = makeApiMethodRequest("groups.info", "channel" -> channelId)
    extract[Group](res, "group")
  }

  def inviteToGroup(channelId: String, userId: String)(implicit ec: ExecutionContext): Future[Group] = {
    val res = makeApiMethodRequest("groups.invite", "channel" -> channelId, "user" -> userId)
    extract[Group](res, "group")
  }

  def kickFromGroup(channelId: String, userId: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("groups.kick", "channel" -> channelId, "user" -> userId)
    extract[Boolean](res, "ok")
  }

  def leaveGroup(channelId: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("groups.leave", "channel" -> channelId)
    extract[Boolean](res, "ok")
  }

  def listGroups(excludeArchived: Int = 0)(implicit ec: ExecutionContext): Future[Seq[Group]] = {
    val res = makeApiMethodRequest("groups.list", "exclude_archived" -> excludeArchived.toString)
    extract[Seq[Group]](res, "groups")
  }

  def markGroup(channelId: String, ts: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("groups.mark", "channel" -> channelId, "ts" -> ts)
    extract[Boolean](res, "ok")
  }

  def openGroup(channelId: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("groups.open", "channel" -> channelId)
    extract[Boolean](res, "ok")
  }

  // TODO: Lite Group Object
  def renameGroup(channelId: String, name: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("groups.rename", "channel" -> channelId, "name" -> name)
    extract[Boolean](res, "ok")
  }

  def setGroupPurpose(channelId: String, purpose: String)(implicit ec: ExecutionContext): Future[String] = {
    val res = makeApiMethodRequest("groups.setPurpose", "channel" -> channelId, "purpose" -> purpose)
    extract[String](res, "purpose")
  }

  def setGroupTopic(channelId: String, topic: String)(implicit ec: ExecutionContext): Future[String] = {
    val res = makeApiMethodRequest("groups.setTopic", "channel" -> channelId, "topic" -> topic)
    extract[String](res, "topic")
  }

  def unarchiveGroup(channelId: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("groups.unarchive", "channel" -> channelId)
    extract[Boolean](res, "ok")
  }

  /************************/
  /****  IM Endpoints  ****/
  /************************/

  def closeIm(channelId: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("im.close", "channel" -> channelId)
    extract[Boolean](res, "ok")
  }

  def getImHistory(channelId: String, latest: Option[String] = None, oldest: Option[String] = None,
      inclusive: Option[Int] = None, count: Option[Int] = None)(implicit ec: ExecutionContext): Future[HistoryChunk] = {
    val res = makeApiMethodRequest (
      "im.history",
      "channel" -> channelId,
      "latest" -> latest,
      "oldest" -> oldest,
      "inclusive" -> inclusive,
      "count" -> count)
    res.map(_.as[HistoryChunk])
  }

  def listIms()(implicit ec: ExecutionContext): Future[Seq[Im]] = {
    val res = makeApiMethodRequest("im.list")
    extract[Seq[Im]](res, "ims")
  }

  def markIm(channelId: String, ts: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("im.mark", "channel" -> channelId, "ts" -> ts)
    extract[Boolean](res, "ok")
  }

  def openIm(userId: String)(implicit ec: ExecutionContext): Future[String] = {
    val res = makeApiMethodRequest("im.open", "user" -> userId)
    res.map(r => (r \ "channel" \ "id").as[String])
  }

  /*******************************/
  /****  Migration Endpoints  ****/
  /*******************************/

  def exchangeUserIds(userIds: Seq[String], toOld: Boolean = false)(implicit ec: ExecutionContext): Future[MigrationExchangeResponse] = {
    val res = makeApiMethodRequest("migration.exchange", "users" -> userIds.mkString(","), "to_old" -> toOld)
    res.map(_.as[MigrationExchangeResponse])
  }

  /**************************/
  /****  MPIM Endpoints  ****/
  /**************************/

  def openMpim(userIds: Seq[String])(implicit ec: ExecutionContext): Future[String] = {
    val res = makeApiMethodRequest("mpim.open", "users" -> userIds.mkString(","))
    res.map(r => (r \ "group" \ "id").as[String])
  }

  def closeMpim(channelId: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("mpim.close", "channel" -> channelId)
    extract[Boolean](res, "ok")
  }

  def listMpims()(implicit ec: ExecutionContext): Future[Seq[Group]] = {
    val res = makeApiMethodRequest("mpim.list")
    extract[Seq[Group]](res, "groups")
  }

  def markMpim(channelId: String, ts: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("mpim.mark", "channel" -> channelId, "ts" -> ts)
    extract[Boolean](res, "ok")
  }

  def getMpimHistory(channelId: String, latest: Option[String] = None, oldest: Option[String] = None,
                   inclusive: Option[Int] = None, count: Option[Int] = None)(implicit ec: ExecutionContext): Future[HistoryChunk] = {
    val res = makeApiMethodRequest (
      "mpim.history",
      "channel" -> channelId,
      "latest" -> latest,
      "oldest" -> oldest,
      "inclusive" -> inclusive,
      "count" -> count)
    res.map(_.as[HistoryChunk])
  }

  /**************************/
  /****  Pins Endpoints  ****/
  /**************************/

  def addPin(channelId: String, file: Option[String] = None, fileComment: Option[String] = None,
                  timestamp: Option[String] = None)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("pins.add", "channel" -> channelId,
      "file" -> file, "file_comment" -> fileComment, "timestamp" -> timestamp)
    extract[Boolean](res, "ok")
  }

  def addPinToMessage(channelId: String, timestamp: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("pins.add", "channel" -> channelId, "timestamp" -> timestamp)
    extract[Boolean](res, "ok")
  }

  def listPins(channelId: String)(implicit ec: ExecutionContext): Future[PinsResponse] = {
    val res = makeApiMethodRequest("pins.list", "channel" -> channelId)
    res.map(_.as[PinsResponse])
  }

  def removePin(channelId: String, file: Option[String] = None, fileComment: Option[String] = None,
                timestamp: Option[String] = None)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("pins.remove", "channel" -> channelId,
      "file" -> file, "file_comment" -> fileComment, "timestamp" -> timestamp)
    extract[Boolean](res, "ok")
  }

  def removePinFromMessage(channelId: String, timestamp: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("pins.remove", "channel" -> channelId, "timestamp" -> timestamp)
    extract[Boolean](res, "ok")
  }

  /******************************/
  /****  Reaction Endpoints  ****/
  /******************************/

  def addReaction(emojiName: String, file: Option[String] = None, fileComment: Option[String] = None, channelId: Option[String] = None,
                    timestamp: Option[String] = None)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("reactions.add", "name" -> emojiName, "file" -> file, "file_comment" -> fileComment,
                                        "channel" -> channelId, "timestamp" -> timestamp)
    extract[Boolean](res, "ok")
  }

  def addReactionToMessage(emojiName: String, channelId: String, timestamp: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    addReaction(emojiName = emojiName, channelId = Some(channelId), timestamp = Some(timestamp))
  }

  def getReactions(file: Option[String] = None, fileComment: Option[String] = None, channelId: Option[String] = None,
                    timestamp: Option[String] = None, full: Option[Boolean] = None)(implicit ec: ExecutionContext): Future[Seq[Reaction]] = {
    val res = makeApiMethodRequest("reactions.get", "file" -> file, "file_comment" -> fileComment, "channel" -> channelId,
                                            "timestamp" -> timestamp, "full" -> full)
    res.map(r => (r \\ "reactions").headOption.map(_.as[Seq[Reaction]]).getOrElse(Seq[Reaction]()))
  }

  def getReactionsForMessage(channelId: String, timestamp: String, full: Option[Boolean] = None)(implicit ec: ExecutionContext): Future[Seq[Reaction]] = {
    getReactions(channelId = Some(channelId), timestamp = Some(timestamp), full = full)
  }

  def listReactionsForUser(userId: Option[String], full: Boolean = false, count: Option[Int] = None, page: Option[Int] = None)(implicit ec: ExecutionContext): Future[ReactionsResponse] = {
    val res = makeApiMethodRequest("reations.list", "user" -> userId, "full" -> full, "count" -> count, "page" -> page)
    res.map(_.as[ReactionsResponse])
  }

  def removeReaction(emojiName: String, file: Option[String] = None, fileComment: Option[String] = None, channelId: Option[String] = None,
                    timestamp: Option[String] = None)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("reactions.remove", "name" -> emojiName, "file" -> file, "file_comment" -> fileComment,
                                        "channel" -> channelId, "timestamp" -> timestamp)
    extract[Boolean](res, "ok")
  }

  def removeReactionFromMessage(emojiName: String, channelId: String, timestamp: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    removeReaction(emojiName = emojiName, channelId = Some(channelId), timestamp = Some(timestamp))
  }

  /*************************/
  /****  RTM Endpoints  ****/
  /*************************/

  def startRealTimeMessageSession()(implicit ec: ExecutionContext): Future[RtmStartState] = {
    val res = makeApiMethodRequest("rtm.start", "include_locale" -> true)
    res.map(_.as[RtmStartState])
  }


  /****************************/
  /****  Search Endpoints  ****/
  /****************************/

  // TODO: Return proper search results (not JsValue)
  def searchAll(query: String, sort: Option[String] = None, sortDir: Option[String] = None, highlight: Option[String] = None,
      count: Option[Int] = None, page: Option[Int] = None)(implicit ec: ExecutionContext): Future[JsValue] = {
    makeApiMethodRequest (
      "search.all",
      "query" -> query,
      "sort" -> sort,
      "sortDir" -> sortDir,
      "highlight" -> highlight,
      "count" -> count,
      "page" -> page)
  }

  // TODO: Return proper search results (not JsValue)
  def searchFiles(query: String, sort: Option[String] = None, sortDir: Option[String] = None, highlight: Option[String] = None,
      count: Option[Int] = None, page: Option[Int] = None)(implicit ec: ExecutionContext): Future[JsValue] = {
    makeApiMethodRequest (
      "search.files",
      "query" -> query,
      "sort" -> sort,
      "sortDir" -> sortDir,
      "highlight" -> highlight,
      "count" -> count,
      "page" -> page)
  }

  // TODO: Return proper search results (not JsValue)
  def searchMessages(query: String, sort: Option[String] = None, sortDir: Option[String] = None, highlight: Option[String] = None,
      count: Option[Int] = None, page: Option[Int] = None)(implicit ec: ExecutionContext): Future[JsValue] = {
    makeApiMethodRequest (
      "search.messages",
      "query" -> query,
      "sort" -> sort,
      "sortDir" -> sortDir,
      "highlight" -> highlight,
      "count" -> count,
      "page" -> page)
  }

  /***************************/
  /****  Stars Endpoints  ****/
  /***************************/

  // TODO: Return proper star items (not JsValue)
  def listStars(userId: Option[String] = None, count: Option[Int] = None, page: Option[Int] = None)(implicit ec: ExecutionContext): Future[JsValue] = {
    makeApiMethodRequest("start.list", "user" -> userId, "count" -> count, "page" -> page)
  }


  /**************************/
  /****  Team Endpoints  ****/
  /**************************/

  // TODO: Parse actual result type: https://api.slack.com/methods/team.accessLogs
  def getTeamAccessLogs(count: Option[Int], page: Option[Int])(implicit ec: ExecutionContext): Future[JsValue] = {
    makeApiMethodRequest("team.accessLogs", "count" -> count, "page" -> page)
  }

  // TODO: Parse actual value type: https://api.slack.com/methods/team.info
  def getTeamInfo()(implicit ec: ExecutionContext): Future[JsValue] = {
    makeApiMethodRequest("team.info")
  }


  /**************************/
  /****  User Endpoints  ****/
  /**************************/

  // TODO: Full payload for authed user: https://api.slack.com/methods/users.getPresence
  def getUserPresence(userId: String)(implicit ec: ExecutionContext): Future[String] = {
    val res = makeApiMethodRequest("users.getPresence", "user" -> userId)
    extract[String](res, "presence")
  }

  def getUserInfo(userId: String)(implicit ec: ExecutionContext): Future[User] = {
    val res = makeApiMethodRequest("users.info", "user" -> userId)
    extract[User](res, "user")
  }

  def listUsers()(implicit ec: ExecutionContext): Future[Seq[User]] = {
    val res = makeApiMethodRequest("users.list")
    extract[Seq[User]](res, "members")
  }

  def setUserActive(userId: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("users.setActive", "user" -> userId)
    extract[Boolean](res, "ok")
  }

  def setUserPresence(presence: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    val res = makeApiMethodRequest("users.setPresence", "presence" -> presence)
    extract[Boolean](res, "ok")
  }

  def lookupUserByEmail(emailId: String)(implicit ec: ExecutionContext): Future[User] = {
    val res = makeApiMethodRequest("users.lookupByEmail", "email" -> emailId)
    extract[User](res, "user")
  }

  /*****************************/
  /****  Private Functions  ****/
  /*****************************/

  private def makeApiMethodRequest(apiMethod: String, queryParams: (String,Any)*)(implicit ec: ExecutionContext): Future[JsValue] = {
    val req = apiBaseWithToken / apiMethod
    makeApiRequest(addQueryParams(req, cleanParams(queryParams)))
  }

  private def makeApiJsonRequest(apiMethod: String, json: JsValue)(implicit ec: ExecutionContext): Future[JsValue] = {
    val req = (apiBase / apiMethod).setMethod("POST")
      .addHeader("Content-Type", "application/json; charset=utf-8")
      .addHeader("Authorization", s"Bearer $token")
      .setBody(json.toString())
    makeApiRequest(req)
  }
}

case class InvalidResponseError(status: Int, body: String) extends Exception(s"Bad status code from Slack: $status")
case class ApiError(code: String, message: Option[String] = None) extends Exception(code)

case class HistoryChunk (
  latest: Option[String],
  messages: Seq[JsValue],
  has_more: Boolean
)

case class RepliesChunk (
  has_more: Boolean,
  messages: Seq[JsValue],
  ok: Boolean
)

case class FileInfo (
  file: SlackFile,
  comments: Seq[SlackComment],
  paging: PagingObject
)

case class FilesResponse (
  files: Seq[SlackFile],
  paging: PagingObject
)

case class MigrationExchangeResponse (
  team_id: String,
  enterprise_id: String,
  user_id_map: Map[String, String],
  invalid_user_ids: Option[Seq[String]]
)

case class ReactionsResponse (
  items: Seq[JsValue], // TODO: Parse out each object type w/ reactions
  paging: PagingObject
)

case class PinsResponse (
  items: Seq[JsValue]
)

case class PagingObject (
  count: Int,
  total: Int,
  page: Int,
  pages: Int
)

case class AccessToken (
  access_token: String,
  scope: String
)

case class RtmStartState (
  url: String,
  self: User,
  team: Team,
  users: Seq[User],
  channels: Seq[Channel],
  groups: Seq[Group],
  ims: Seq[Im],
  bots: Seq[JsValue]
)