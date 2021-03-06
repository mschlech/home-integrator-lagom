package de.softwareschmied.homeintegrator.impl

import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Concat, RestartSource, Sink, Source}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import de.softwareschmied.homedataintegration.{HomeCollector, HomeData}
import de.softwareschmied.homeintegratorlagom.api.HomeIntegratorService
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class HomeIntegratorServiceImpl(system: ActorSystem, persistentEntityRegistry: PersistentEntityRegistry, homeDataRepository: HomeDataRepository) extends
  HomeIntegratorService {
  private val log = LoggerFactory.getLogger(classOf[HomeIntegratorServiceImpl])
  private val homeCollector = new HomeCollector
  private val homeDataMathFunctions = new HomeDataMathFunctions
  private val fetchInterval = system.settings.config.getDuration("fetchInterval", TimeUnit.MILLISECONDS).milliseconds

  override def homeData(intervalS: Int) = ServiceCall { tickMessage =>
    Future.successful(RestartSource.withBackoff(
      minBackoff = 3.seconds,
      maxBackoff = 30.seconds,
      randomFactor = 0.2 // adds 20% "noise" to vary the intervals slightly
    ) { () =>
      Source.tick(0 millis, intervalS seconds, "TICK").map((_) => homeCollector.collectData)
    })
  }

  private val targetSize = 40

  override def homeDataFilteredByTimestamp(intervalS: Int, from: Int) = ServiceCall { _ =>
    val tickSource = RestartSource.withBackoff(
      minBackoff = 3.seconds,
      maxBackoff = 30.seconds,
      randomFactor = 0.2 // adds 20% "noise" to vary the intervals slightly
    ) { () =>
      Source.tick(0 millis, intervalS seconds, "TICK").map((_) => homeCollector.collectData)
    }
    val pastHomeDatas = Await.result(homeDataRepository.getHomeDataSince(from), 120 seconds).to[scala.collection.immutable.Seq]
    log.info("Found: {} homeDatas. Target size: {}", pastHomeDatas.size, targetSize)
    var source: Source[HomeData, NotUsed] = null
    if (pastHomeDatas.size > targetSize) {
      val chunkSize = pastHomeDatas.size / targetSize
      val chunkedPastHomeDatas = pastHomeDatas.grouped(chunkSize).map(x => homeDataMathFunctions.averageHomeData(x)).to[scala.collection.immutable.Seq]
      log.info("Found {} homeDatas and divided them to: {} averaged homeDatas", pastHomeDatas.size, chunkedPastHomeDatas.size)
      source = Source(chunkedPastHomeDatas)
    } else {
      source = Source(pastHomeDatas)
    }
    Future.successful(Source.combine(source, tickSource)(Concat(_)))
  }

  override def pastHomeData = ServiceCall {
    _ => homeDataRepository.getHomeDataSince(1515339914)
  }
}

class HomeDataFetchScheduler(system: ActorSystem, persistentEntityRegistry: PersistentEntityRegistry)(implicit val mat: Materializer,
                                                                                                      ec: ExecutionContext) {
  private val log = LoggerFactory.getLogger(classOf[HomeDataFetchScheduler])
  val fetchInterval = system.settings.config.getDuration("fetchInterval", TimeUnit.MILLISECONDS).milliseconds

  val homeCollector = new HomeCollector
  val source = RestartSource.withBackoff(
    minBackoff = 3.seconds,
    maxBackoff = 30.seconds,
    randomFactor = 0.2 // adds 20% "noise" to vary the intervals slightly
  ) { () =>
    Source.tick(0 millis, fetchInterval, "TICK").map((_) => homeCollector.collectData)
  }
  val sink = Sink.foreach[HomeData](homeData => {
    log.info("persisting: {}", homeData.toString)
    persistentEntityRegistry.refFor[HomeDataEntity](homeData.timestamp.toString).ask(CreateHomeData(homeData))
  })

  system.scheduler.scheduleOnce(1 seconds) {
    source.runWith(sink)
  }
}
