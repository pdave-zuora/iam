package org.hypto.iam

import com.codahale.metrics.Slf4jReporter
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.gson.*
import io.ktor.http.*
import io.ktor.locations.*
import io.ktor.metrics.dropwizard.*
import java.util.concurrent.TimeUnit
import io.ktor.routing.*
import io.ktor.util.*
import com.typesafe.config.ConfigFactory
import io.ktor.auth.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.config.HoconApplicationConfig
import org.openapitools.server.infrastructure.*
import org.hypto.iam.apis.ActionApi
import org.hypto.iam.apis.CredentialApi
import org.hypto.iam.apis.OrganizationApi
import org.hypto.iam.apis.PolicyApi
import org.hypto.iam.apis.ResourceTypeApi
import org.hypto.iam.apis.TokenApi
import org.hypto.iam.apis.UsersApi


internal val settings = HoconApplicationConfig(ConfigFactory.defaultApplication(HTTP::class.java.classLoader))

object HTTP {
    val client = HttpClient(Apache)
}

@KtorExperimentalAPI
@KtorExperimentalLocationsAPI
fun Application.main() {
    install(DefaultHeaders)
    install(DropwizardMetrics) {
        val reporter = Slf4jReporter.forRegistry(registry)
            .outputTo(log)
            .convertRatesTo(TimeUnit.SECONDS)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .build()
        reporter.start(10, TimeUnit.SECONDS)
    }
    install(ContentNegotiation) {
        register(ContentType.Application.Json, GsonConverter())
    }
    install(AutoHeadResponse) // see https://ktor.io/docs/autoheadresponse.html
    install(Compression, ApplicationCompressionConfiguration()) // see https://ktor.io/docs/compression.html
    install(HSTS, ApplicationHstsConfiguration()) // see https://ktor.io/docs/hsts.html
    install(Locations) // see https://ktor.io/docs/features-locations.html
    install(Authentication) {
        basic("access_token") {
        validate { credentials ->
            // TODO: "Apply your basic authentication functionality."
            // Accessible in-method via call.principal<UserIdPrincipal>()
            if (credentials.name == "Swagger" && "Codegen" == credentials.password) {
                UserIdPrincipal(credentials.name)
            } else {
                null
            }
        }
        basic("refresh_token") {
        validate { credentials ->
            // TODO: "Apply your basic authentication functionality."
            // Accessible in-method via call.principal<UserIdPrincipal>()
            if (credentials.name == "Swagger" && "Codegen" == credentials.password) {
                UserIdPrincipal(credentials.name)
            } else {
                null
            }
        }
    }
    install(Routing) {
        ActionApi()
        CredentialApi()
        OrganizationApi()
        PolicyApi()
        ResourceTypeApi()
        TokenApi()
        UsersApi()
    }

}
