package slack.models

import play.api.libs.json.JsValue

case class Conversation (
  id: String,
  name: String,
  created: Long,
  creator: Option[String],
  is_archived: Option[Boolean],
  is_member: Option[Boolean],
  is_private: Option[Boolean],
  is_general: Option[Boolean],
  is_channel: Option[Boolean],
  is_group: Option[Boolean],
  is_im: Option[Boolean],
  is_mpim: Option[Boolean],
  is_read_only: Option[Boolean],
  is_shared: Option[Boolean],
  is_ext_shared: Option[Boolean],
  is_org_shared: Option[Boolean],
  num_members: Option[Int],
  members: Option[Seq[String]],
  topic: Option[ChannelValue],
  purpose: Option[ChannelValue],
  last_read: Option[String],
  latest: Option[JsValue],
  unread_count: Option[Int],
  unread_count_display: Option[Int]
)