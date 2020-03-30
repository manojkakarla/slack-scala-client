package slack.models

case class Conversation (
  id: String,
  name: String,
  created: Long,
  is_channel: Boolean,
  is_group: Boolean,
  is_im: Boolean,
  is_mpim: Boolean,
  is_archived: Boolean,
  is_private: Option[Boolean],
  creator: Option[String],
  is_member: Option[Boolean],
  is_general: Option[Boolean],
  is_read_only: Option[Boolean],
  is_shared: Option[Boolean],
  is_ext_shared: Option[Boolean],
  is_org_shared: Option[Boolean],
  num_members: Option[Int],
  members: Option[Seq[String]],
  topic: Option[ChannelValue],
  purpose: Option[ChannelValue],
  last_read: Option[String]
)