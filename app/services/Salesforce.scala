package services

import org.joda.time.DateTime
import play.api.Application
import play.api.libs.json.{JsArray, JsObject, Json, JsValue}
import play.api.libs.ws.{WSResponse, WSRequestHolder, WS}
import play.api.http.{Status, HeaderNames}
import play.api.mvc.Results.EmptyContent
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future

class Salesforce(implicit app: Application) {

  lazy val authFuture: Future[Auth] = {
    val params = Map(
      "grant_type" -> "password",
      "client_id" -> app.configuration.getString("salesforce.consumer.key").get,
      "client_secret" -> app.configuration.getString("salesforce.consumer.secret").get,
      "username" -> app.configuration.getString("salesforce.username").get,
      "password" -> app.configuration.getString("salesforce.password").get
    )

    WS.
      url("https://login.salesforce.com/services/oauth2/token").
      withQueryString(params.toSeq:_*).
      post(EmptyContent()).
      flatMap { response =>
        response.status match {
          case Status.OK =>
            Future.successful(Auth((response.json \ "access_token").as[String], (response.json \ "instance_url").as[String]))
          case _ =>
            Future.failed(new IllegalStateException(s"Auth Denied: ${response.body}"))
        }
      }
  }


  def ws(path: String): Future[WSRequestHolder] = {
    authFuture.map { auth =>
      WS.
        url(s"${auth.instance}/services/data/v32.0/$path").
        withHeaders(HeaderNames.AUTHORIZATION -> s"Bearer ${auth.token}")
    }
  }

  def insertRefrigTemp(refrig: String, value: Float): Future[JsValue] = {
    val json = Json.obj(
      "Refrigerator__c" -> refrig,
      "Moment__c" -> DateTime.now(),
      "Value__c" -> value
    )

    ws("sobjects/Refrigerator_Temperature_Reading__c").flatMap {
      _.post(json).flatMap { response =>
        response.status match {
          case Status.CREATED =>
            Future.successful(response.json)
          case _ =>
            Future.failed(new IllegalStateException(response.json.toString()))
        }
      }
    }
  }

  def insertRefrigDoor(refrig: String, value: Boolean): Future[JsValue] = {
    val json = Json.obj(
      "Refrigerator__c" -> refrig,
      "Moment__c" -> DateTime.now(),
      "Open__c" -> value
    )

    ws("sobjects/Refrigerator_Door_Reading__c").flatMap {
      _.post(json).flatMap { response =>
        response.status match {
          case Status.CREATED =>
            Future.successful(response.json)
          case _ =>
            Future.failed(new IllegalStateException(response.json.toString()))
        }
      }
    }
  }

  def insertCabinetDoor(cabinet: String, value: Boolean): Future[JsValue] = {
    val json = Json.obj(
      "Cabinet__c" -> cabinet,
      "Moment__c" -> DateTime.now(),
      "Open__c" -> value
    )

    ws("sobjects/Cabinet_Door_Reading__c").flatMap {
      _.post(json).flatMap { response =>
        response.status match {
          case Status.CREATED =>
            Future.successful(response.json)
          case _ =>
            Future.failed(new IllegalStateException(response.json.toString()))
        }
      }
    }
  }

  def getRefrig(id: String): Future[JsValue] = {
    ws(s"sobjects/Refrigerator__c/$id").flatMap {
      _.get().flatMap { response =>
        response.status match {
          case Status.OK =>
            Future.successful(response.json)
          case _ =>
            Future.failed(new IllegalStateException(response.json.toString()))
        }
      }
    }
  }

  def getRefrigCases(id: String): Future[JsArray] = {
    val soql = s"SELECT Id FROM Case WHERE Refrigerator__c = '$id' AND Status <> 'Closed'"
    ws(s"query/").flatMap {
      _.withQueryString("q" -> soql).get().flatMap { response =>
        response.status match {
          case Status.OK =>
            Future.successful((response.json \ "records").as[JsArray])
          case _ =>
            Future.failed(new IllegalStateException(response.json.toString()))
        }
      }
    }
  }

  def getCabinetCases(id: String): Future[JsArray] = {
    val soql = s"SELECT Id FROM Case WHERE Cabinet__c = '$id' AND Status <> 'Closed'"
    ws(s"query/").flatMap {
      _.withQueryString("q" -> soql).get().flatMap { response =>
        response.status match {
          case Status.OK =>
            Future.successful((response.json \ "records").as[JsArray])
          case _ =>
            Future.failed(new IllegalStateException(response.json.toString()))
        }
      }
    }
  }

  def createRefrigCase(id: String, temp: Float): Future[JsValue] = {
    val json = Json.obj(
      "Refrigerator__c" -> id,
      "Origin" -> "Web",
      "Subject" -> s"Refrigerator temperature is $temp"
    )
    ws("sobjects/Case").flatMap {
      _.post(json).flatMap { response =>
        response.status match {
          case Status.CREATED =>
            Future.successful(response.json)
          case _ =>
            Future.failed(new IllegalStateException(response.json.toString()))
        }
      }
    }
  }

  def createCabinetCase(id: String): Future[JsValue] = {
    val json = Json.obj(
      "Cabinet__c" -> id,
      "Origin" -> "Web",
      "Subject" -> "A Cabinet Door Was Unexpectedly Opened"
    )
    ws("sobjects/Case").flatMap {
      _.post(json).flatMap { response =>
        response.status match {
          case Status.CREATED =>
            Future.successful(response.json)
          case _ =>
            Future.failed(new IllegalStateException(response.json.toString()))
        }
      }
    }
  }

  def latestRefrigDoorReadings(id: String): Future[JsArray] = {
    val soql = s"SELECT Id, Moment__c, Open__c FROM Refrigerator_Door_Reading__c WHERE Refrigerator__c = '$id' ORDER BY Moment__c DESC"
    ws(s"query/").flatMap {
      _.withQueryString("q" -> soql).get().flatMap { response =>
        response.status match {
          case Status.OK =>
            Future.successful((response.json \ "records").as[JsArray])
          case _ =>
            Future.failed(new IllegalStateException(response.json.toString()))
        }
      }
    }
  }

  def escalateCase(id: String): Future[JsValue] = {
    val json = Json.obj(
      "Status" -> "Escalated",
      "Priority" -> "High"
    )
    ws(s"sobjects/Case/$id").flatMap {
      _.patch(json).flatMap { response =>
        response.status match {
          case Status.NO_CONTENT =>
            addCaseComments(id, "Refrigerator door has been open for more than 1 minute")
          case _ =>
            Future.failed(new IllegalStateException("Could not escalate case"))
        }
      }
    }
  }

  def addCaseComments(id: String, comments: String): Future[JsValue] = {
    val json = Json.obj(
      "ParentId" -> id,
      "CommentBody" -> comments
    )
    ws(s"sobjects/CaseComment").flatMap {
      _.post(json).flatMap { response =>
        response.status match {
          case Status.OK =>
            Future.successful(response.json)
          case _ =>
            Future.failed(new IllegalStateException(response.json.toString()))
        }
      }
    }
  }

}

object Salesforce {
  def apply(implicit app: Application) = new Salesforce()
}

case class Auth(token: String, instance: String)