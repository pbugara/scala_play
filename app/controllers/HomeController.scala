package controllers

import javax.inject._

import play.api.mvc._

import be.objectify.deadbolt.scala.{ActionBuilders, DeadboltActions}
import be.objectify.deadbolt.scala.cache.HandlerCache
import com.google.inject.Inject
import models._
import play.api.mvc.{Action, Controller}
import services.UserService

import scala.concurrent.{Await, Future}
import play.api.i18n.{I18nSupport, Messages, MessagesApi}
import play.api.libs.concurrent.Execution.Implicits._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.matching.Regex
import org.mindrot.jbcrypt._

@Singleton
class HomeController @Inject()(cc: ControllerComponents, deadbolt: DeadboltActions, handlers: HandlerCache)
  extends AbstractController(cc) {

  def index = Action { implicit request =>
    var isLogged: Boolean = false
    if(request != null && request.session != null && !request.session.get("email").isEmpty) {
      isLogged = true
    }
    println(isLogged)
    println(request.session)
    println(request.session.get("email"))
    if(isLogged) {
      val email = request.session.get("email").get
      Ok(views.html.authorizedIndex(email))
    } else {
      Ok(views.html.index("Strona główna projektu DSL"))
    }
  }
}
