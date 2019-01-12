package controllers

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

class UserController @Inject()
(userService: UserService, deadbolt: DeadboltActions, handlers: HandlerCache, actionBuilder: ActionBuilders,
 val messagesApi: MessagesApi) extends Controller with I18nSupport {

  val SPECIAL_CHARACTERS = "[~!@#$^%&*\\(\\)_+={}\\[\\]|;:\"'<,>.?` /\\\\-]"
  val EMAIL_BUSY_ERR_MSG = "Adres email istnieje"
  val PASSWORD_UPPERCASE_LETTER_ERR_MSG = "Hasło musi zawierać conajmniej jeden duży znak"
  val PASSWORD_ONE_SPECIAL_SIGN_ERR_MSG = "Hasło musi zawierać conajmniej jeden znak specjalny"
  val PASSWORD_INVALID_SIZE_ERR_MSG = "Hasło musi zawierać conajmniej 5 znaków"
  val LOGIN_ERR_MSG = "Błędny login lub hasło"

  var errors = mutable.HashMap[String, String]()

  /*
  Books
   */
  def userBooks = deadbolt.SubjectPresent()() { implicit request =>
    val email = request.session.get("email").get
    val user = Await.result(userService.findByEmail(email), Duration.Inf).get
    val books = Await.result(userService.getUserBooks(user.id), Duration.Inf).seq
    Future(Ok(views.html.books(books)))
  }

  def addBook = Action.async { implicit request =>

    val email = request.session.get("email").get
    val user = Await.result(userService.findByEmail(email), Duration.Inf).get
    val books = Await.result(userService.getUserBooks(user.id), Duration.Inf).seq

    val bookForm = BookForm.form.bindFromRequest()
    bookForm.fold(
      errorForm => Future(BadRequest(views.html.books(books))),
      data => {
        val book = Book(0, user.id, data.title, data.category)
        userService.addBook(book)
        Future(Redirect(routes.UserController.userBooks()))
      }
    )
  }

  def getBookToEdit(id: Long) = deadbolt.SubjectPresent()() {
    implicit request =>
      val userBook = Await.result(userService.getBook(id), Duration.Inf).get
      val bookForm = BookForm.form.fill(BookFormData(userBook.id, userBook.title, userBook.category))
      Future(Ok(views.html.editBook(bookForm)))
  }

  def editBook = Action.async { implicit request =>
    val email = request.session.get("email").get
    val user = Await.result(userService.findByEmail(email), Duration.Inf).get
    val books = Await.result(userService.getUserBooks(user.id), Duration.Inf).seq
    val bookForm = BookForm.form.bindFromRequest()
    bookForm.fold(
      errorForm => Future(BadRequest(views.html.books(books))),
      data => {
        val bookId = data.id
        val book = Book(bookId, user.id, data.title, data.category)
        println(bookId)
        userService.updateBook(book)
        Future(Redirect(routes.UserController.userBooks()))
      }
    )
  }

  def deleteBook(id: Long) = deadbolt.SubjectPresent()() {
    implicit request =>
      userService.deleteBook(id) map {
        res =>
          Redirect(routes.UserController.userBooks())
      }
  }

  def newBook = deadbolt.SubjectPresent()() { implicit request =>
    val bookForm = BookForm.form.fill(BookFormData(0, "",""))
    Future(Ok(views.html.addBook(BookForm.form)))
  }

  def changePasswordView = deadbolt.SubjectPresent()() { implicit request =>
    Future(Ok(views.html.changePassword(ChangePasswordForm.form)))
  }

  def changePassword() = Action.async { implicit request =>
    var changePasswordForm = ChangePasswordForm.form.bindFromRequest
    changePasswordForm.fold(
      errorForm => Future(BadRequest(views.html.changePassword(errorForm))),
      data => {
        errors.clear()
        if(data.password != data.rePassword) {
          changePasswordForm = play.api.data.Form(changePasswordForm.mapping, changePasswordForm.data,
            Seq(new play.api.data.FormError("password", "Hasła się różnią")), changePasswordForm.value)
          errors += ("passwordError" -> passwordVerify(data.password))
        }
        if (passwordVerify(data.password) != null) {
          changePasswordForm = play.api.data.Form(changePasswordForm.mapping, changePasswordForm.data,
            Seq(new play.api.data.FormError("password", passwordVerify(data.password))), changePasswordForm.value)
          errors += ("passwordError" -> passwordVerify(data.password))
        }

        if (errors.size == 0) {
          val email = request.session.get("email").get
          val user = Await.result(userService.findByEmail(email), Duration.Inf).get
          val passwordHash = org.mindrot.jbcrypt.BCrypt.hashpw(data.password, BCrypt.gensalt)
          val updatedUser = User(user.id, user.firstName, user.lastName, user.mobile, user.email, passwordHash)
          userService.updateUser(updatedUser)
          Future(Redirect(routes.UserController.userBooks()))
        }
        else {
          Future(BadRequest(views.html.changePassword(changePasswordForm)))
        }
      })
  }

    /*
  Login
   */
  def login = deadbolt.SubjectNotPresent()() { implicit request =>
    Future(Ok(views.html.login(LoginForm.form, null)))
  }

  def logout = deadbolt.SubjectPresent()() { implicit request =>
    Future(Ok(views.html.login(LoginForm.form, null)).withNewSession)
  }

  def signIn() = Action.async { implicit request =>
    val loginForm = LoginForm.form.bindFromRequest()
    loginForm.fold(
      errorForm => Future(BadRequest(views.html.login(errorForm, LOGIN_ERR_MSG))),
      data => {
        if (Await.result(userService.findByEmail(data.email), Duration.Inf) != None) {
          val user = Await.result(userService.findByEmail(data.email), Duration.Inf).get
          if(BCrypt.checkpw(data.password, user.password)) {
            Future(Redirect(routes.UserController.userBooks()).withSession("email" -> data.email))
          }
          else {
            Future(Unauthorized(views.html.login(loginForm, LOGIN_ERR_MSG)))
          }
        } else {
          Future(Unauthorized(views.html.login(loginForm, LOGIN_ERR_MSG)))
        }
      })
  }

  def registration = deadbolt.SubjectNotPresent()() { implicit request =>
    Future(Ok(views.html.registration(RegistrationForm.form, errors)))
  }

  def addUser() = Action.async { implicit request =>
    var registrationForm = RegistrationForm.form.bindFromRequest
    println(registrationForm)
    registrationForm.fold(
      errorForm => Future(BadRequest(views.html.registration(errorForm, errors))),
      data => {
        val passwordHash = org.mindrot.jbcrypt.BCrypt.hashpw(data.password, BCrypt.gensalt)
        val newUser = User(0, data.firstName, data.lastName, data.mobile, data.email, passwordHash)
        errors.clear()
        if (passwordVerify(data.password) != null) {
          registrationForm = play.api.data.Form(registrationForm.mapping, registrationForm.data,
            Seq(new play.api.data.FormError("password", passwordVerify(data.password))), registrationForm.value)
          errors += ("passwordError" -> passwordVerify(data.password))
        }
        if (Await.result(userService.findByEmail(data.email), 1.second) != None) {
          registrationForm = play.api.data.Form(registrationForm.mapping, registrationForm.data,
            Seq(new play.api.data.FormError("email", EMAIL_BUSY_ERR_MSG)), registrationForm.value)
          errors += ("emailError" -> EMAIL_BUSY_ERR_MSG)
        }

        if (errors.size == 0) {
          userService.addUser(newUser).map(res =>
            Redirect(routes.UserController.login()).flashing(Messages("flash.success") -> res)
          )
        }
        else {
          Future(BadRequest(views.html.registration(registrationForm, errors)))
        }
      })
  }

  def passwordVerify(password: String): String = {
    val specialCharacters = new Regex(SPECIAL_CHARACTERS)

    password.length >= 5 match {
      case true =>
        specialCharacters.findFirstMatchIn(password) match {
          case Some(_) =>
            password.exists(_.isUpper) match {
              case true => null
              case false => return PASSWORD_UPPERCASE_LETTER_ERR_MSG
            }
          case None => return PASSWORD_ONE_SPECIAL_SIGN_ERR_MSG
        }
      case false => return PASSWORD_INVALID_SIZE_ERR_MSG
    }
  }

  def deleteUser(id: Long) = deadbolt.SubjectNotPresent()() {
    implicit request =>
      userService.deleteUser(id) map {
        res =>
          Redirect(routes.UserController.registration())
      }
  }

}