package controllers

import org.joda.time.DateTime
import play.api.{Logger, Play}
import play.api.mvc.{Action, Controller}
import services.Salesforce
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object Application extends Controller {

  val salesforce = Salesforce(Play.current)

  def ping = Action.async(parse.json) { request =>
    val refrig = (request.body \ "refrig").as[String]
    val cabinet = (request.body \ "cabinet").as[String]
    val temp = (request.body \ "temp").as[Float]
    val button = (request.body \ "button").as[Int] == 1

    val refrigTempFuture = salesforce.insertRefrigTemp(refrig, temp)
    val refrigDoorFuture = salesforce.insertRefrigDoor(refrig, button)
    val cabinetDoorFuture = salesforce.insertCabinetDoor(cabinet, button)

    val tempCaseFuture = salesforce.getRefrig(refrig).flatMap { refrigJson =>
      val high = (refrigJson \ "High_Temperature_Threshold__c").as[Float]
      val low = (refrigJson \ "Low_Temperature_Threshold__c").as[Float]

      if ((temp > high) || (temp < low)) {
        salesforce.getRefrigCases(refrig).flatMap { casesJson =>
          if (casesJson.value.size > 0) {
            val caseId = (casesJson.value.head \ "Id").as[String]
            // a case exists
            salesforce.latestRefrigDoorReadings(refrig).flatMap { refrigDoorReadings =>
              val pastMinute = refrigDoorReadings.value.filter { reading =>
                val momentString = (reading \ "Moment__c").as[String]
                val moment = DateTime.parse(momentString)
                val minuteAgo = DateTime.now().minusMinutes(1)
                moment.isAfter(minuteAgo)
              }

              val everClosed = pastMinute.exists { reading =>
                !(reading \ "Open__c").as[Boolean]
              }

              if (!everClosed) {
                // door has been open for a minute
                salesforce.escalateCase(caseId).recover {
                  // most likely because it has already been escalated
                  case e: Exception => casesJson
                }
              }
              else {
                Future.successful(casesJson)
              }
            }
          }
          else {
            salesforce.createRefrigCase(refrig, temp)
          }
        }
      }
      else {
        // all is good
        Future.successful(refrigJson)
      }
    }

    val cabinetDoorCaseFuture = salesforce.getCabinetCases(cabinet).flatMap { cabinetCases =>
      if ((cabinetCases.value.size == 0) && (button)) {
        salesforce.createCabinetCase(cabinet)
      }
      else {
        Future.successful(cabinetCases)
      }
    }

    val f = for {
      refrigTempResponse <- refrigTempFuture
      refrigDoorResponse <- refrigDoorFuture
      cabinetDoorResponse <- cabinetDoorFuture
      tempCaseResponse <- tempCaseFuture
      cabinetDoorCaseResponse <- cabinetDoorCaseFuture
    } yield {
      (refrigTempResponse, refrigDoorResponse, cabinetDoorResponse, tempCaseResponse, cabinetDoorCaseResponse)
    }

    f.map { _ =>
      Ok
    } recover {
      case e: Exception =>
        Logger.error(e.toString)
        InternalServerError(e.getMessage)
    }
  }

}
