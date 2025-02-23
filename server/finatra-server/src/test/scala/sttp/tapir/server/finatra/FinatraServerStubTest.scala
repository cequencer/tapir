package sttp.tapir.server.finatra

import com.twitter.util.Future
import sttp.client3.testing.SttpBackendStub
import sttp.tapir.server.finatra.FinatraServerInterpreter.FutureMonadError
import sttp.tapir.server.interceptor.CustomInterceptors
import sttp.tapir.server.tests.{CreateServerStubTest, ServerStubTest}

import scala.concurrent.Promise

object FinatraCreateServerStubTest extends CreateServerStubTest[Future, FinatraServerOptions] {
  override def customInterceptors: CustomInterceptors[Future, FinatraServerOptions] = FinatraServerOptions.customInterceptors
  override def stub[R]: SttpBackendStub[Future, R] = SttpBackendStub(FutureMonadError)
  override def asFuture[A]: Future[A] => concurrent.Future[A] = f => {
    val p = Promise[A]
    f.onFailure(p.failure)
    f.onSuccess(p.success)
    p.future
  }
}

class FinatraServerStubTest extends ServerStubTest(FinatraCreateServerStubTest)
