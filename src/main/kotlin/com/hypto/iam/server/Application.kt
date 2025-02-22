@file:Suppress("LongMethod", "ComplexMethod")

package com.hypto.iam.server

import com.hypto.iam.server.apis.actionApi
import com.hypto.iam.server.apis.authProviderApi
import com.hypto.iam.server.apis.createOrganizationApi
import com.hypto.iam.server.apis.createPasscodeApi
import com.hypto.iam.server.apis.createUserPasswordApi
import com.hypto.iam.server.apis.createUsersApi
import com.hypto.iam.server.apis.credentialApi
import com.hypto.iam.server.apis.deleteOrganizationApi
import com.hypto.iam.server.apis.getAndUpdateOrganizationApi
import com.hypto.iam.server.apis.keyApi
import com.hypto.iam.server.apis.linkUserApi
import com.hypto.iam.server.apis.loginApi
import com.hypto.iam.server.apis.passcodeApis
import com.hypto.iam.server.apis.policyApi
import com.hypto.iam.server.apis.resetPasswordApi
import com.hypto.iam.server.apis.resourceApi
import com.hypto.iam.server.apis.subOrganizationsApi
import com.hypto.iam.server.apis.tokenApi
import com.hypto.iam.server.apis.userAuthApi
import com.hypto.iam.server.apis.usersApi
import com.hypto.iam.server.apis.validationApi
import com.hypto.iam.server.configs.AppConfig
import com.hypto.iam.server.db.repositories.MasterKeysRepo
import com.hypto.iam.server.di.applicationModule
import com.hypto.iam.server.di.controllerModule
import com.hypto.iam.server.di.repositoryModule
import com.hypto.iam.server.security.Authorization
import com.hypto.iam.server.service.DatabaseFactory.pool
import com.hypto.iam.server.utils.ApplicationIdUtil
import com.sksamuel.cohort.Cohort
import com.sksamuel.cohort.HealthCheckRegistry
import com.sksamuel.cohort.db.DatabaseConnectionHealthCheck
import com.sksamuel.cohort.hikari.HikariDataSourceManager
import com.sksamuel.cohort.hikari.HikariPendingThreadsHealthCheck
import com.sksamuel.cohort.logback.LogbackManager
import com.sksamuel.cohort.threads.ThreadDeadlockHealthCheck
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.addShutdownHook
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.plugins.hsts.HSTS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.IgnoreTrailingSlash
import io.ktor.server.routing.Routing
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.SLF4JLogger
import java.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val REQUEST_ID_HEADER = "X-Request-Id"
const val ROOT_ORG = "hypto-root"

private const val MAX_THREADS_WAITING_FOR_DB_CONNS = 100

@Suppress("ThrowsCount")
fun Application.handleRequest() {
    val idGenerator: ApplicationIdUtil.Generator by inject()
    val micrometerRegistry: MeterRegistry by inject()
    val micrometerBindings: List<MeterBinder> by inject()

    install(DefaultHeaders)
    install(CallLogging) {
        callIdMdc("call-id")
    }
    install(CallId) {
        retrieveFromHeader(REQUEST_ID_HEADER)
        generate { idGenerator.requestId() }
        verify { it.isNotEmpty() }
        replyToHeader(headerName = REQUEST_ID_HEADER)
    }
    install(MicrometerMetrics) {
        registry = micrometerRegistry
        meterBinders = micrometerBindings
        @Suppress("MagicNumber")
        timers { _, _ ->
            publishPercentileHistogram(true)
            publishPercentiles(0.9, 0.95)
            maximumExpectedValue(Duration.ofSeconds(20)) // Upper bound to limit buckets in histograms
        }
    }
    install(ContentNegotiation) {
        // TODO: Switch to kotlinx.serialization
        gson()
    }
    install(StatusPages) {
        statusPages()
    }
    install(DoubleReceive)
    install(AutoHeadResponse) // see http://ktor.io/features/autoheadresponse.html
    install(HSTS, applicationHstsConfiguration()) // see http://ktor.io/features/hsts.html
    install(Compression, applicationCompressionConfiguration()) // see http://ktor.io/features/compression.html
    install(Authentication, applicationAuthenticationConfiguration())

    // Create a signing Master key pair in case one doesn't exist
    val masterKeysRepo: MasterKeysRepo by inject()
    runBlocking {
        masterKeysRepo.rotateKey(skipIfPresent = true)
    }

    install(Authorization) {
    }

    install(IgnoreTrailingSlash) {}

    install(Routing) {
        authenticate("hypto-iam-root-auth", "signup-passcode-auth", "oauth") {
            createOrganizationApi()
        }

        authenticate("hypto-iam-root-auth") {
            deleteOrganizationApi()
        }

        authenticate("bearer-auth", "invite-passcode-auth") {
            createUsersApi()
            createUserPasswordApi()
        }

        authenticate("bearer-auth") {
            getAndUpdateOrganizationApi()
            actionApi()
            credentialApi()
            policyApi()
            resourceApi()
            usersApi()
            userAuthApi()
            validationApi()
            passcodeApis()
            subOrganizationsApi()
            linkUserApi()
        }

        authenticate("reset-passcode-auth") {
            resetPasswordApi()
        }

        tokenApi() // Authentication handled along with API definitions
        loginApi()
        keyApi()
        authProviderApi()

        authenticate("optional-bearer-auth") {
            createPasscodeApi()
        }
    }

    install(Cohort) {
        endpointPrefix = "admin"
        logManager = LogbackManager
        // show connection pool information
        dataSources = listOf(HikariDataSourceManager(pool))

        healthcheck(
            "/status",
            HealthCheckRegistry(Dispatchers.Default) {
                // detects if threads are mutually blocked on each others locks
                register("ThreadDeadlockHealthCheck", ThreadDeadlockHealthCheck(), ZERO, 1.minutes)
                register(
                    "HikariPendingThreadsHealthCheck",
                    HikariPendingThreadsHealthCheck(pool, MAX_THREADS_WAITING_FOR_DB_CONNS),
                    ZERO,
                    1.minutes,
                )
                register("DatabaseHealthCheck", DatabaseConnectionHealthCheck(pool), ZERO, 1.minutes)
                // TODO: Configure the below check based on infra set-up
                // register(DiskSpaceHealthCheck(/*FileStore for root(/) */, 1.0)), 1.minutes)
            },
        )
    }
}

fun Application.module() {
    install(Koin) {
        SLF4JLogger()
        modules(repositoryModule, controllerModule, applicationModule)
    }
    handleRequest()
}

fun main() {
    val appConfig: AppConfig = AppConfig.configuration

    embeddedServer(
        CIO,
        appConfig.server.port,
        module = Application::module,
        configure = {
            // Refer io.ktor.server.engine.ApplicationEngine.Configuration
            connectionGroupSize = appConfig.server.connectionGroupSize
            workerGroupSize = appConfig.server.workerGroupSize
            callGroupSize = appConfig.server.callGroupSize
        },
    ).apply {
        addShutdownHook {
            runBlocking {
                shutdown(this@apply)
            }
        }
    }.start(wait = true)
}

private val preWait = 1.seconds
private val gracePeriod = 30.seconds
private val timeout = 45.seconds

private suspend fun shutdown(engine: ApplicationEngine) {
    delay(preWait)
    val log = engine.application.environment.log
    log.info("Shutting down HTTP server...")
    engine.stop(gracePeriod.inWholeMilliseconds, timeout.inWholeMilliseconds)
    log.info("HTTP server shutdown!!")
}
