package tapir.server.tests

import java.io.{ByteArrayInputStream, File, InputStream}
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

import cats.data.NonEmptyList
import cats.effect.{ContextShift, IO, Resource}
import cats.implicits._
import com.softwaremill.sttp._
import com.softwaremill.sttp.asynchttpclient.cats.AsyncHttpClientCatsBackend
import io.circe.generic.auto._
import org.scalatest.{Assertion, BeforeAndAfterAll, FunSuite, Matchers}
import tapir._
import tapir.json.circe._
import tapir.model.{MultiQueryParams, Part, SetCookieValue, UsernamePassword}
import tapir.server.{DecodeFailureHandler, ServerDefaults}
import tapir.tests.TestUtil._
import tapir.tests._

import scala.reflect.ClassTag

trait ServerTests[R[_], S, ROUTE] extends FunSuite with Matchers with BeforeAndAfterAll {

  // method matching

  testServer(endpoint, "GET empty endpoint")((_: Unit) => pureResult(().asRight[Unit])) { baseUri =>
    sttp.get(baseUri).send().map(_.body shouldBe Right(""))
  }

  testServer(endpoint, "POST empty endpoint")((_: Unit) => pureResult(().asRight[Unit])) { baseUri =>
    sttp.post(baseUri).send().map(_.body shouldBe Right(""))
  }

  testServer(endpoint.get, "GET a GET endpoint")((_: Unit) => pureResult(().asRight[Unit])) { baseUri =>
    sttp.get(baseUri).send().map(_.body shouldBe Right(""))
  }

  testServer(endpoint.get, "POST a GET endpoint")((_: Unit) => pureResult(().asRight[Unit])) { baseUri =>
    sttp.post(baseUri).send().map(_.body shouldBe 'left)
  }

  //

  testServer(in_query_out_string)((fruit: String) => pureResult(s"fruit: $fruit".asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri?fruit=orange").send().map(_.body shouldBe Right("fruit: orange"))
  }

  testServer[String, Nothing, String](in_query_out_infallible_string)((fruit: String) => pureResult(s"fruit: $fruit".asRight[Nothing])) {
    baseUri =>
      sttp.get(uri"$baseUri?fruit=kiwi").send().map(_.body shouldBe Right("fruit: kiwi"))
  }

  testServer(in_query_query_out_string) { case (fruit: String, amount: Option[Int]) => pureResult(s"$fruit $amount".asRight[Unit]) } {
    baseUri =>
      sttp.get(uri"$baseUri?fruit=orange").send().map(_.body shouldBe Right("orange None")) *>
        sttp.get(uri"$baseUri?fruit=orange&amount=10").send().map(_.body shouldBe Right("orange Some(10)"))
  }

  testServer(in_header_out_string)((p1: String) => pureResult(s"$p1".asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri").header("X-Role", "Admin").send().map(_.body shouldBe Right("Admin"))
  }

  testServer(in_path_path_out_string) { case (fruit: String, amount: Int) => pureResult(s"$fruit $amount".asRight[Unit]) } { baseUri =>
    sttp.get(uri"$baseUri/fruit/orange/amount/20").send().map(_.body shouldBe Right("orange 20"))
  }

  testServer(in_path, "Empty path should not be passed to path capture decoding") { _ =>
    pureResult(Right(()))
  } { baseUri =>
    sttp.get(uri"$baseUri/api/").send().map(_.code shouldBe StatusCodes.NotFound)
  }

  testServer(in_two_path_capture, "capturing two path parameters with the same specification") {
    case (a: Int, b: Int) => pureResult(Right((a, b)))
  } { baseUri =>
    sttp.get(uri"$baseUri/in/12/23").send().map { response =>
      response.header("a") shouldBe Some("12")
      response.header("b") shouldBe Some("23")
    }
  }

  testServer(in_string_out_string)((b: String) => pureResult(b.asRight[Unit])) { baseUri =>
    sttp.post(uri"$baseUri/api/echo").body("Sweet").send().map(_.body shouldBe Right("Sweet"))
  }

  testServer(in_string_out_string, "with get method")((b: String) => pureResult(b.asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri/api/echo").body("Sweet").send().map(_.body shouldBe 'left)
  }

  testServer(in_mapped_query_out_string)((fruit: List[Char]) => pureResult(s"fruit length: ${fruit.length}".asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri?fruit=orange").send().map(_.body shouldBe Right("fruit length: 6"))
  }

  testServer(in_mapped_path_out_string)((fruit: Fruit) => pureResult(s"$fruit".asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri/fruit/kiwi").send().map(_.body shouldBe Right("Fruit(kiwi)"))
  }

  testServer(in_mapped_path_path_out_string)((p1: FruitAmount) => pureResult(s"FA: $p1".asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri/fruit/orange/amount/10").send().map(_.body shouldBe Right("FA: FruitAmount(orange,10)"))
  }

  testServer(in_query_mapped_path_path_out_string) {
    case (fa: FruitAmount, color: String) => pureResult(s"FA: $fa color: $color".asRight[Unit])
  } { baseUri =>
    sttp
      .get(uri"$baseUri/fruit/orange/amount/10?color=yellow")
      .send()
      .map(_.body shouldBe Right("FA: FruitAmount(orange,10) color: yellow"))
  }

  testServer(in_query_out_mapped_string)((p1: String) => pureResult(p1.toList.asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri?fruit=orange").send().map(_.body shouldBe Right("orange"))
  }

  testServer(in_query_out_mapped_string_header)((p1: String) => pureResult(FruitAmount(p1, p1.length).asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri?fruit=orange").send().map { r =>
      r.body shouldBe Right("orange")
      r.header("X-Role") shouldBe Some("6")
    }
  }

  testServer(in_header_before_path, "Header input before path capture input") {
    case (str: String, i: Int) => pureResult((i, str).asRight[Unit])
  } { baseUri =>
    sttp.get(uri"$baseUri/12").header("SomeHeader", "hello").send().map { response =>
      response.body shouldBe Right("hello")
      response.header("IntHeader") shouldBe Some("12")
    }
  }

  testServer(in_json_out_json)((fa: FruitAmount) => pureResult(FruitAmount(fa.fruit + " banana", fa.amount * 2).asRight[Unit])) { baseUri =>
    sttp
      .post(uri"$baseUri/api/echo")
      .body("""{"fruit":"orange","amount":11}""")
      .send()
      .map(_.body shouldBe Right("""{"fruit":"orange banana","amount":22}"""))
  }

  testServer(in_json_out_json, "with accept header")((fa: FruitAmount) => pureResult(fa.asRight[Unit])) { baseUri =>
    sttp
      .post(uri"$baseUri/api/echo")
      .body("""{"fruit":"banana","amount":12}""")
      .header(HeaderNames.Accept, MediaTypes.Json)
      .send()
      .map(_.body shouldBe Right("""{"fruit":"banana","amount":12}"""))
  }

  testServer(in_json_out_json, "content type")((fa: FruitAmount) => pureResult(fa.asRight[Unit])) { baseUri =>
    sttp
      .post(uri"$baseUri/api/echo")
      .body("""{"fruit":"banana","amount":12}""")
      .send()
      .map(_.contentType shouldBe Some(MediaType.Json().mediaType))
  }

  testServer(in_byte_array_out_byte_array)((b: Array[Byte]) => pureResult(b.asRight[Unit])) { baseUri =>
    sttp.post(uri"$baseUri/api/echo").body("banana kiwi".getBytes).send().map(_.body shouldBe Right("banana kiwi"))
  }

  testServer(in_byte_buffer_out_byte_buffer)((b: ByteBuffer) => pureResult(b.asRight[Unit])) { baseUri =>
    sttp.post(uri"$baseUri/api/echo").body("mango").send().map(_.body shouldBe Right("mango"))
  }

  testServer(in_input_stream_out_input_stream)(
    (is: InputStream) => pureResult((new ByteArrayInputStream(inputStreamToByteArray(is)): InputStream).asRight[Unit])
  ) { baseUri =>
    sttp.post(uri"$baseUri/api/echo").body("mango").send().map(_.body shouldBe Right("mango"))
  }

  testServer(in_unit_out_string, "default status mapper")((_: Unit) => pureResult("".asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri/not-existing-path").send().map(_.code shouldBe StatusCodes.NotFound)
  }

  testServer(in_unit_error_out_string, "default error status mapper")((_: Unit) => pureResult("".asLeft[Unit])) { baseUri =>
    sttp.get(uri"$baseUri/api").send().map(_.code shouldBe StatusCodes.BadRequest)
  }

  testServer(in_file_out_file)((file: File) => pureResult(file.asRight[Unit])) { baseUri =>
    sttp.post(uri"$baseUri/api/echo").body("pen pineapple apple pen").send().map(_.body shouldBe Right("pen pineapple apple pen"))
  }

  testServer(in_form_out_form)((fa: FruitAmount) => pureResult(fa.copy(fruit = fa.fruit.reverse, amount = fa.amount + 1).asRight[Unit])) {
    baseUri =>
      sttp
        .post(uri"$baseUri/api/echo")
        .body(Map("fruit" -> "plum", "amount" -> "10"))
        .send()
        .map(_.body shouldBe Right("fruit=mulp&amount=11"))
  }

  testServer(in_query_params_out_string)(
    (mqp: MultiQueryParams) => pureResult(mqp.toSeq.sortBy(_._1).map(p => s"${p._1}=${p._2}").mkString("&").asRight[Unit])
  ) { baseUri =>
    val params = Map("name" -> "apple", "weight" -> "42", "kind" -> "very good")
    sttp
      .get(uri"$baseUri/api/echo/params?$params")
      .send()
      .map(_.body shouldBe Right("kind=very good&name=apple&weight=42"))
  }

  testServer(in_headers_out_headers)((hs: Seq[(String, String)]) => pureResult(hs.map(h => h.copy(_2 = h._2.reverse)).asRight[Unit])) {
    baseUri =>
      sttp
        .get(uri"$baseUri/api/echo/headers")
        .headers(("X-Fruit", "apple"), ("Y-Fruit", "Orange"))
        .send()
        .map(_.headers should contain allOf (("X-Fruit", "elppa"), ("Y-Fruit", "egnarO")))
  }

  testServer(in_paths_out_string)((ps: Seq[String]) => pureResult(ps.mkString(" ").asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri/hello/it/is/me/hal").send().map(_.body shouldBe Right("hello it is me hal"))
  }

  testServer(in_paths_out_string, "paths should match empty path")((ps: Seq[String]) => pureResult(ps.mkString(" ").asRight[Unit])) {
    baseUri =>
      sttp.get(uri"$baseUri").send().map(_.body shouldBe Right(""))
  }

  testServer(in_stream_out_stream[S])((s: S) => pureResult(s.asRight[Unit])) { baseUri =>
    sttp.post(uri"$baseUri/api/echo").body("pen pineapple apple pen").send().map(_.body shouldBe Right("pen pineapple apple pen"))
  }

  testServer(in_query_list_out_header_list)((l: List[String]) => pureResult(("v0" :: l).reverse.asRight[Unit])) { baseUri =>
    sttp
      .get(uri"$baseUri/api/echo/param-to-header?qq=${List("v1", "v2", "v3")}")
      .send()
      .map { r =>
        r.headers.filter(_._1 == "hh").map(_._2).toList shouldBe List("v3", "v2", "v1", "v0")
      }
  }

  testServer(in_simple_multipart_out_multipart)(
    (fa: FruitAmount) => pureResult(FruitAmount(fa.fruit + " apple", fa.amount * 2).asRight[Unit])
  ) { baseUri =>
    sttp
      .post(uri"$baseUri/api/echo/multipart")
      .multipartBody(multipart("fruit", "pineapple"), multipart("amount", "120"))
      .send()
      .map { r: Response[String] =>
        r.unsafeBody should include regex "name=\"fruit\"[\\s\\S]*pineapple apple"
        r.unsafeBody should include regex "name=\"amount\"[\\s\\S]*240"
      }
  }

  testServer(in_file_multipart_out_multipart)(
    (fd: FruitData) =>
      pureResult(
        FruitData(Part(writeToFile(readFromFile(fd.data.body).reverse)).header("X-Auth", fd.data.header("X-Auth").toString)).asRight[Unit]
      )
  ) { baseUri =>
    val file = writeToFile("peach mario")
    sttp
      .post(uri"$baseUri/api/echo/multipart")
      .multipartBody(multipartFile("data", file).fileName("fruit-data.txt").header("X-Auth", "12"))
      .send()
      .map { r =>
        r.unsafeBody should include regex "name=\"data\"[\\s\\S]*oiram hcaep"
        r.unsafeBody should include regex "X-Auth: Some\\(12\\)"
      }
  }

  testServer(in_query_out_string, "invalid query parameter")((fruit: String) => pureResult(s"fruit: $fruit".asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri?fruit2=orange").send().map(_.code shouldBe StatusCodes.BadRequest)
  }

  testServer(in_cookie_cookie_out_header)((p: (Int, String)) => pureResult(List(p._1.toString.reverse, p._2.reverse).asRight[Unit])) {
    baseUri =>
      sttp.get(uri"$baseUri/api/echo/headers").cookies(("c1", "23"), ("c2", "pomegranate")).send().map { r =>
        r.headers("Cookie") shouldBe Seq("32", "etanargemop")
      }
  }

  testServer(in_cookies_out_cookies)(
    (cs: List[tapir.model.Cookie]) => pureResult(cs.map(c => tapir.model.SetCookie(c.name, c.value.reverse)).asRight[Unit])
  ) { baseUri =>
    sttp.get(uri"$baseUri/api/echo/headers").cookies(("c1", "v1"), ("c2", "v2")).send().map { r =>
      r.cookies.map(c => (c.name, c.value)).toList shouldBe List(("c1", "1v"), ("c2", "2v"))
    }
  }

  testServer(in_set_cookie_value_out_set_cookie_value)((c: SetCookieValue) => pureResult(c.copy(value = c.value.reverse).asRight[Unit])) {
    baseUri =>
      sttp.get(uri"$baseUri/api/echo/headers").header("Set-Cookie", "c1=xy; HttpOnly; Path=/").send().map { r =>
        r.cookies.toList shouldBe List(
          com.softwaremill.sttp.Cookie("c1", "yx", None, None, None, Some("/"), secure = false, httpOnly = true)
        )
      }
  }

  testServer(in_string_out_content_type_string, "dynamic content type")((b: String) => pureResult((b, "image/png").asRight[Unit])) {
    baseUri =>
      sttp.get(uri"$baseUri/api/echo").body("test").send().map { r =>
        r.contentType shouldBe Some("image/png")
        r.unsafeBody shouldBe "test"
      }
  }

  testServer(in_unit_out_header_redirect)(_ => pureResult("http://new.com".asRight[Unit])) { baseUri =>
    sttp.followRedirects(false).get(uri"$baseUri").send().map { r =>
      r.code shouldBe StatusCodes.PermanentRedirect
      r.header("Location") shouldBe Some("http://new.com")
    }
  }

  testServer(in_unit_out_fixed_header)(_ => pureResult(().asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri").send().map { r =>
      r.header("Location") shouldBe Some("Poland")
    }
  }

  testServer(in_optional_json_out_optional_json)((fa: Option[FruitAmount]) => pureResult(fa.asRight[Unit])) { baseUri =>
    sttp
      .post(uri"$baseUri/api/echo")
      .send()
      .map { r =>
        r.code shouldBe StatusCodes.Ok
        r.body shouldBe Right("")
      } >>
      sttp
        .post(uri"$baseUri/api/echo")
        .body("""{"fruit":"orange","amount":11}""")
        .send()
        .map(_.body shouldBe Right("""{"fruit":"orange","amount":11}"""))
  }

  // path matching

  testServer(endpoint, "no path should match anything")((_: Unit) => pureResult(Either.right[Unit, Unit](()))) { baseUri =>
    sttp.get(uri"$baseUri").send().map(_.code shouldBe StatusCodes.Ok) >>
      sttp.get(uri"$baseUri/").send().map(_.code shouldBe StatusCodes.Ok) >>
      sttp.get(uri"$baseUri/nonemptypath").send().map(_.code shouldBe StatusCodes.Ok) >>
      sttp.get(uri"$baseUri/nonemptypath/nonemptypath2").send().map(_.code shouldBe StatusCodes.Ok)
  }

  testServer(in_root_path, "root path should not match non-root path")((_: Unit) => pureResult(Either.right[Unit, Unit](()))) { baseUri =>
    sttp.get(uri"$baseUri/nonemptypath").send().map(_.code shouldBe StatusCodes.NotFound)
  }

  testServer(in_root_path, "root path should match empty path")((_: Unit) => pureResult(Either.right[Unit, Unit](()))) { baseUri =>
    sttp.get(uri"$baseUri").send().map(_.code shouldBe StatusCodes.Ok)
  }

  testServer(in_root_path, "root path should match root path")((_: Unit) => pureResult(Either.right[Unit, Unit](()))) { baseUri =>
    sttp.get(uri"$baseUri/").send().map(_.code shouldBe StatusCodes.Ok)
  }

  testServer(in_single_path, "single path should match single path")((_: Unit) => pureResult(Either.right[Unit, Unit](()))) { baseUri =>
    sttp.get(uri"$baseUri/api").send().map(_.code shouldBe StatusCodes.Ok)
  }

  testServer(in_single_path, "single path should match single/ path")((_: Unit) => pureResult(Either.right[Unit, Unit](()))) { baseUri =>
    sttp.get(uri"$baseUri/api/").send().map(_.code shouldBe StatusCodes.Ok)
  }

  testServer(in_path_paths_out_header_body, "Capturing paths after path capture") {
    case (i, paths) =>
      pureResult(Right((i, paths.mkString(","))))
  } { baseUri =>
    sttp.get(uri"$baseUri/api/15/and/some/more/path").send().map { r =>
      r.code shouldBe StatusCodes.Ok
      r.header("IntPath") shouldBe Some("15")
      r.body shouldBe Right("some,more,path")
    }
  }

  testServer(in_path_paths_out_header_body, "Capturing paths after path capture (when empty)") {
    case (i, paths) =>
      pureResult(Right((i, paths.mkString(","))))
  } { baseUri =>
    sttp.get(uri"$baseUri/api/15/and/").send().map { r =>
      r.code shouldBe StatusCodes.Ok
      r.header("IntPath") shouldBe Some("15")
      r.body shouldBe Right("")
    }
  }

  testServer(in_single_path, "single path should not match root path")((_: Unit) => pureResult(Either.right[Unit, Unit](()))) { baseUri =>
    sttp.get(uri"$baseUri").send().map(_.code shouldBe StatusCodes.NotFound) >>
      sttp.get(uri"$baseUri/").send().map(_.code shouldBe StatusCodes.NotFound)
  }

  testServer(in_single_path, "single path should not match larger path")((_: Unit) => pureResult(Either.right[Unit, Unit](()))) { baseUri =>
    sttp.get(uri"$baseUri/api/echo/hello").send().map(_.code shouldBe StatusCodes.NotFound) >>
      sttp.get(uri"$baseUri/api/echo/").send().map(_.code shouldBe StatusCodes.NotFound)
  }

  testServer(in_string_out_status_from_string)((v: String) => pureResult((if (v == "apple") Right("x") else Left(10)).asRight[Unit])) {
    baseUri =>
      sttp.get(uri"$baseUri?fruit=apple").send().map(_.code shouldBe StatusCodes.Ok) >>
        sttp.get(uri"$baseUri?fruit=orange").send().map(_.code shouldBe StatusCodes.Accepted)
  }

  testServer(in_string_out_status_from_string_one_empty)(
    (v: String) => pureResult((if (v == "apple") Right("x") else Left(())).asRight[Unit])
  ) { baseUri =>
    sttp.get(uri"$baseUri?fruit=apple").send().map(_.code shouldBe StatusCodes.Ok) >>
      sttp.get(uri"$baseUri?fruit=orange").send().map(_.code shouldBe StatusCodes.Accepted)
  }

  testServer(in_extract_request_out_string)((v: String) => pureResult(v.asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri").send().map(_.unsafeBody shouldBe "GET") >>
      sttp.post(uri"$baseUri").send().map(_.unsafeBody shouldBe "POST")
  }

  testServer(in_string_out_status)(
    (v: String) => pureResult((if (v == "apple") StatusCodes.Accepted else StatusCodes.NotFound).asRight[Unit])
  ) { baseUri =>
    sttp.get(uri"$baseUri?fruit=apple").send().map(_.code shouldBe StatusCodes.Accepted) >>
      sttp.get(uri"$baseUri?fruit=orange").send().map(_.code shouldBe StatusCodes.NotFound)
  }

  // path shape matching

  val decodeFailureHandlerBadRequestOnPathFailure: DecodeFailureHandler[Any] = ServerDefaults.decodeFailureHandlerUsingResponse(
    ServerDefaults.failureResponse,
    badRequestOnPathFailureIfPathShapeMatches = true,
    ServerDefaults.validationErrorToMessage
  )

  testServer(
    in_path_fixed_capture_fixed_capture,
    "Returns 400 if path 'shape' matches, but failed to parse a path parameter",
    Some(decodeFailureHandlerBadRequestOnPathFailure)
  )(
    _ => pureResult(Either.right[Unit, Unit](()))
  ) { baseUri =>
    sttp.get(uri"$baseUri/customer/asd/orders/2").send().map { response =>
      response.body shouldBe Left("Invalid value for: path parameter customer_id")
      response.code shouldBe StatusCodes.BadRequest
    }
  }

  testServer(
    in_path_fixed_capture_fixed_capture,
    "Returns 404 if path 'shape' doesn't match",
    Some(decodeFailureHandlerBadRequestOnPathFailure)
  )(
    _ => pureResult(Either.right[Unit, Unit](()))
  ) { baseUri =>
    sttp.get(uri"$baseUri/customer").send().map(response => response.code shouldBe StatusCodes.NotFound) >>
      sttp.get(uri"$baseUri/customer/asd").send().map(response => response.code shouldBe StatusCodes.NotFound) >>
      sttp.get(uri"$baseUri/customer/asd/orders/2/xyz").send().map(response => response.code shouldBe StatusCodes.NotFound)
  }

  // auth

  testServer(in_auth_apikey_header_out_string)((s: String) => pureResult(s.asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri/auth").header("X-Api-Key", "1234").send().map(_.unsafeBody shouldBe "1234")
  }

  testServer(in_auth_apikey_query_out_string)((s: String) => pureResult(s.asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri/auth?api-key=1234").send().map(_.unsafeBody shouldBe "1234")
  }

  testServer(in_auth_basic_out_string)((up: UsernamePassword) => pureResult(up.toString.asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri/auth").auth.basic("teddy", "bear").send().map(_.unsafeBody shouldBe "UsernamePassword(teddy,Some(bear))")
  }

  testServer(in_auth_bearer_out_string)((s: String) => pureResult(s.asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri/auth").auth.bearer("1234").send().map(_.unsafeBody shouldBe "1234")
  }

  //

  testServer(
    "two endpoints with increasingly specific path inputs: should match path exactly",
    NonEmptyList.of(
      route(endpoint.get.in("p1").out(stringBody), (_: Unit) => pureResult("e1".asRight[Unit])),
      route(endpoint.get.in("p1" / "p2").out(stringBody), (_: Unit) => pureResult("e2".asRight[Unit]))
    )
  ) { baseUri =>
    sttp.get(uri"$baseUri/p1").send().map(_.unsafeBody shouldBe "e1") >>
      sttp.get(uri"$baseUri/p1/p2").send().map(_.unsafeBody shouldBe "e2")
  }

  testServer(
    "two endpoints with a body defined as the first input: should only consume body when then path matches",
    NonEmptyList.of(
      route(
        endpoint.post.in(binaryBody[Array[Byte]]).in("p1").out(stringBody),
        (s: Array[Byte]) => pureResult(s"p1 ${s.length}".asRight[Unit])
      ),
      route(
        endpoint.post.in(binaryBody[Array[Byte]]).in("p2").out(stringBody),
        (s: Array[Byte]) => pureResult(s"p2 ${s.length}".asRight[Unit])
      )
    )
  ) { baseUri =>
    sttp
      .post(uri"$baseUri/p2")
      .body("a" * 1000000)
      .send()
      .map { r =>
        r.unsafeBody shouldBe "p2 1000000"
      }
  }

  testServer(
    "two endpoints with query defined as the first input, path segments as second input: should try the second endpoint if the path doesn't match",
    NonEmptyList.of(
      route(endpoint.get.in(query[String]("q1")).in("p1"), (_: String) => pureResult(().asRight[Unit])),
      route(endpoint.get.in(query[String]("q2")).in("p2"), (_: String) => pureResult(().asRight[Unit]))
    )
  ) { baseUri =>
    sttp.get(uri"$baseUri/p1?q1=10").send().map(_.code shouldBe StatusCodes.Ok) >>
      sttp.get(uri"$baseUri/p1?q2=10").send().map(_.code shouldBe StatusCodes.BadRequest) >>
      sttp.get(uri"$baseUri/p2?q2=10").send().map(_.code shouldBe StatusCodes.Ok) >>
      sttp.get(uri"$baseUri/p2?q1=10").send().map(_.code shouldBe StatusCodes.BadRequest)
  }

  testServer(
    "two endpoints with increasingly specific path inputs, first with a required query parameter: should match path exactly",
    NonEmptyList.of(
      route(endpoint.get.in("p1").in(query[String]("q1")).out(stringBody), (_: String) => pureResult("e1".asRight[Unit])),
      route(endpoint.get.in("p1" / "p2").out(stringBody), (_: Unit) => pureResult("e2".asRight[Unit]))
    )
  ) { baseUri =>
    sttp.get(uri"$baseUri/p1/p2").send().map(_.unsafeBody shouldBe "e2")
  }

  //

  def throwFruits(name: String): R[String] = name match {
    case "apple"  => pureResult("ok")
    case "banana" => suspendResult(throw FruitError("no bananas", 102))
    case n        => suspendResult(throw new IllegalArgumentException(n))
  }

  testServer(
    "recover errors from exceptions",
    NonEmptyList.of(
      routeRecoverErrors(endpoint.in(query[String]("name")).errorOut(jsonBody[FruitError]).out(stringBody), throwFruits)
    )
  ) { baseUri =>
    sttp.get(uri"$baseUri?name=apple").send().map(_.body shouldBe Right("ok")) >>
      sttp.get(uri"$baseUri?name=banana").send().map { r =>
        r.code shouldBe StatusCodes.BadRequest
        r.body shouldBe Left("""{"msg":"no bananas","code":102}""")
      } >>
      sttp.get(uri"$baseUri?name=orange").send().map { r =>
        r.code shouldBe StatusCodes.InternalServerError
        r.body shouldBe 'left
      }
  }

  testServer(Validation.in_query_tagged, "support query validation with tagged type")((_: String) => pureResult(().asRight[Unit])) {
    baseUri =>
      sttp.get(uri"$baseUri?fruit=apple").send().map(_.code shouldBe StatusCodes.Ok) >>
        sttp.get(uri"$baseUri?fruit=orange").send().map(_.code shouldBe StatusCodes.BadRequest) >>
        sttp.get(uri"$baseUri?fruit=banana").send().map(_.code shouldBe StatusCodes.Ok)
  }

  testServer(Validation.in_query, "support query validation")((_: Int) => pureResult(().asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri?amount=3").send().map(_.code shouldBe StatusCodes.Ok) >>
      sttp.get(uri"$baseUri?amount=-3").send().map(_.code shouldBe StatusCodes.BadRequest)
  }

  testServer(Validation.in_json_wrapper, "support jsonBody validation with wrapped type")(
    (_: ValidFruitAmount) => pureResult(().asRight[Unit])
  ) { baseUri =>
    sttp.get(uri"$baseUri").body("""{"fruit":"orange","amount":11}""").send().map(_.code shouldBe StatusCodes.Ok) >>
      sttp.get(uri"$baseUri").body("""{"fruit":"orange","amount":0}""").send().map(_.code shouldBe StatusCodes.BadRequest) >>
      sttp.get(uri"$baseUri").body("""{"fruit":"orange","amount":1}""").send().map(_.code shouldBe StatusCodes.Ok)
  }

  testServer(Validation.in_query_wrapper, "support query validation with wrapper type")((_: IntWrapper) => pureResult(().asRight[Unit])) {
    baseUri =>
      sttp.get(uri"$baseUri?amount=11").send().map(_.code shouldBe StatusCodes.Ok) >>
        sttp.get(uri"$baseUri?amount=0").send().map(_.code shouldBe StatusCodes.BadRequest) >>
        sttp.get(uri"$baseUri?amount=1").send().map(_.code shouldBe StatusCodes.Ok)
  }

  testServer(Validation.in_json_collection, "support jsonBody validation with list of wrapped type")(
    (_: BasketOfFruits) => pureResult(().asRight[Unit])
  ) { baseUri =>
    sttp.get(uri"$baseUri").body("""{"fruits":[{"fruit":"orange","amount":11}]}""").send().map(_.code shouldBe StatusCodes.Ok) >>
      sttp.get(uri"$baseUri").body("""{"fruits": []}""").send().map(_.code shouldBe StatusCodes.BadRequest) >>
      sttp.get(uri"$baseUri").body("""{fruits":[{"fruit":"orange","amount":0}]}""").send().map(_.code shouldBe StatusCodes.BadRequest)
  }

  //

  implicit lazy val cs: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)
  implicit val backend: SttpBackend[IO, Nothing] = AsyncHttpClientCatsBackend[IO]()

  override protected def afterAll(): Unit = {
    backend.close()
    super.afterAll()
  }

  //

  type Port = Int

  def pureResult[T](t: T): R[T]
  def suspendResult[T](t: => T): R[T]

  def route[I, E, O](
      e: Endpoint[I, E, O, S],
      fn: I => R[Either[E, O]],
      decodeFailureHandler: Option[DecodeFailureHandler[Any]] = None
  ): ROUTE

  def routeRecoverErrors[I, E <: Throwable, O](e: Endpoint[I, E, O, S], fn: I => R[O])(implicit eClassTag: ClassTag[E]): ROUTE

  def server(routes: NonEmptyList[ROUTE], port: Port): Resource[IO, Unit]

  def testServer[I, E, O](
      e: Endpoint[I, E, O, S],
      testNameSuffix: String = "",
      decodeFailureHandler: Option[DecodeFailureHandler[Any]] = None
  )(
      fn: I => R[Either[E, O]]
  )(runTest: Uri => IO[Assertion]): Unit = {

    testServer(
      e.showDetail + (if (testNameSuffix == "") "" else " " + testNameSuffix),
      NonEmptyList.of(route(e, fn, decodeFailureHandler))
    )(runTest)
  }

  def testServer(name: String, rs: => NonEmptyList[ROUTE])(runTest: Uri => IO[Assertion]): Unit = {
    val resources = for {
      port <- Resource.liftF(IO(nextPort()))
      _ <- server(rs, port)
    } yield uri"http://localhost:$port"

    if (testNameFilter forall name.contains) {
      test(name)(resources.use(runTest).unsafeRunSync())
    }
  }

  // define to run a single test (in development)
  def testNameFilter: Option[String] = None

  //

  def initialPort: Int
  private lazy val _nextPort = new AtomicInteger(initialPort)
  def nextPort(): Port = _nextPort.getAndIncrement()
}
