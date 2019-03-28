package slack.models

import play.api.libs.json._

import scala.util.{Failure, Success, Try}

case class Attachment(
                       fallback: Option[String] = None,
                       callback_id: Option[String] = None,
                       color: Option[String] = None,
                       pretext: Option[String] = None,
                       author_name: Option[String] = None,
                       author_link: Option[String] = None,
                       author_icon: Option[String] = None,
                       title: Option[String] = None,
                       title_link: Option[String] = None,
                       text: Option[String] = None,
                       fields: Option[Seq[AttachmentField]] = None,
                       image_url: Option[String] = None,
                       thumb_url: Option[String] = None,
                       actions: Option[Seq[ActionField]] = None,
                       mrkdwn_in: Option[Seq[String]] = None,
                       footer: Option[String] = None,
                       footer_icon: Option[String] = None,
                       ts: Option[Long] = None
                     )

case class AttachmentField(title: String, value: String, short: Boolean)


case class OptionField(text: String, value: String)

case class ConfirmField(text: String, title: Option[String] = None,
                        ok_text: Option[String] = None, cancel_text: Option[String] = None)


case class ActionField(name: String,
                       text: String, `type`: String,
                       style: Option[String] = None,
                       value: Option[String] = None,
                       url: Option[String] = None,
                       data_source: Option[String] = None,
                       min_query_length: Option[Int] = None,
                       options: Option[Seq[OptionField]] = None,
                       selected_options: Option[Seq[OptionField]] = None,
                       confirm: Option[ConfirmField] = None)

object ActionField {

  val reads: Reads[ActionField] = new Reads[ActionField] {
    override def reads(json: JsValue): JsResult[ActionField] =
      Try {
        val name = (json \ "name").asOpt[String].mkString
        val text = (json \ "text").as[String]
        val `type` = (json \ "type").as[String]
        val style = (json \ "style").asOpt[String]
        val value = (json \ "value").asOpt[String]
        val url = (json \ "url").asOpt[String]
        val ds = (json \ "data_source").asOpt[String]
        val mql = (json \ "min_query_length").asOpt[Int]
        val opts = (json \ "options").asOpt[Seq[OptionField]]
        val selected = (json \ "selected_options").asOpt[Seq[OptionField]]
        val confirm = (json \ "confirm").asOpt[ConfirmField]
        ActionField(name, text, `type`, style, value, url, ds, mql, opts, selected, confirm)
      } match {
        case Success(af) => JsSuccess(af)
        case Failure(e) => JsError(e.getMessage)
      }
  }

  val writes: Writes[ActionField] = new Writes[ActionField] {
    override def writes(o: ActionField): JsValue = {
      val json = Json.obj("name" -> o.name, "text" -> o.text, "type" -> o.`type`)
      Seq(o.style.map(ob => Json.obj("style" -> ob)),
        o.value.map(ob => Json.obj("value" -> ob)),
        o.url.map(ob => Json.obj("url" -> ob)),
        o.data_source.map(ob => Json.obj("data_source" -> ob)),
        o.min_query_length.map(ob => Json.obj("min_query_length" -> ob)),
        o.options.map(ob => Json.obj("options" -> ob)),
        o.selected_options.map(ob => Json.obj("selected_options" -> ob)),
        o.confirm.map(ob => Json.obj("confirm" -> ob))).flatten
        .foldLeft(json)(_ ++ _)
    }
  }

  val format = Format(reads, writes)
}