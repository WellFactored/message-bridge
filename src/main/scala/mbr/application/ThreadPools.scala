package mbr.application

import java.util.concurrent.{ExecutorService, Executors}

import cats.effect.{IO, Resource}

import scala.concurrent.ExecutionContext

object ThreadPools {
  val cachedThreadPool: Resource[IO, ExecutionContext] = ecResource(IO(Executors.newCachedThreadPool()))
  val workStealingPool: Resource[IO, ExecutionContext] = ecResource(IO(Executors.newWorkStealingPool()))

  def fixedThreadPool(count: Int): Resource[IO, ExecutionContext] = ecResource(IO(Executors.newFixedThreadPool(count)))

  def ecResource(executorService: IO[ExecutorService]): Resource[IO, ExecutionContext] =
    Resource
      .make(executorService)(ec => IO(ec.shutdown()))
      .map(ExecutionContext.fromExecutor(_))
}
