/**
* Hypto IAM
* APIs for Hypto IAM Service.
*
* The version of the OpenAPI document: 1.0.0
* Contact: engineering@hypto.in
*
* NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
* https://openapi-generator.tech
* Do not edit the class manually.
*/
package org.hypto.iam.apis

import com.google.gson.Gson
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.http.*
import io.ktor.response.*
import org.hypto.iam.Paths
import io.ktor.locations.*
import io.ktor.routing.*
import org.hypto.iam.infrastructure.ApiPrincipal
import org.hypto.iam.models.ErrorResponse
import org.hypto.iam.models.InlineResponse200
import org.hypto.iam.models.UNKNOWN_BASE_TYPE
import org.hypto.iam.models.User

@KtorExperimentalLocationsAPI
fun Route.UsersApi() {
    val gson = Gson()
    val empty = mutableMapOf<String, Any?>()

    authenticate("access_token") {
    put<Paths.attachPolicies> {
        val principal = call.authentication.principal<UserIdPrincipal>()!!
        
        val exampleContentType = "application/json"
            val exampleContentString = """{
              "success" : true
            }"""
            
            when (exampleContentType) {
                "application/json" -> call.respond(gson.fromJson(exampleContentString, empty::class.java))
                "application/xml" -> call.respondText(exampleContentString, ContentType.Text.Xml)
                else -> call.respondText(exampleContentString)
            }
    }
    }

    authenticate("access_token") {
    post<Paths.createUser> {
        val principal = call.authentication.principal<UserIdPrincipal>()!!
        
        val exampleContentType = "application/json"
            val exampleContentString = """{
              "user_type" : "normal",
              "phone" : "phone",
              "organization_id" : "organization_id",
              "login_access" : true,
              "created_at" : "created_at",
              "id" : "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "created_by" : "046b6c7f-0b8a-43b9-b35d-6489e6daee91",
              "email" : "email",
              "username" : "username",
              "status" : "active"
            }"""
            
            when (exampleContentType) {
                "application/json" -> call.respond(gson.fromJson(exampleContentString, empty::class.java))
                "application/xml" -> call.respondText(exampleContentString, ContentType.Text.Xml)
                else -> call.respondText(exampleContentString)
            }
    }
    }

    authenticate("access_token") {
    delete<Paths.deleteUser> {
        val principal = call.authentication.principal<UserIdPrincipal>()!!
        
        val exampleContentType = "application/json"
            val exampleContentString = """{
              "success" : true
            }"""
            
            when (exampleContentType) {
                "application/json" -> call.respond(gson.fromJson(exampleContentString, empty::class.java))
                "application/xml" -> call.respondText(exampleContentString, ContentType.Text.Xml)
                else -> call.respondText(exampleContentString)
            }
    }
    }

    authenticate("access_token") {
    put<Paths.detachPolicies> {
        val principal = call.authentication.principal<UserIdPrincipal>()!!
        
        val exampleContentType = "application/json"
            val exampleContentString = """{
              "success" : true
            }"""
            
            when (exampleContentType) {
                "application/json" -> call.respond(gson.fromJson(exampleContentString, empty::class.java))
                "application/xml" -> call.respondText(exampleContentString, ContentType.Text.Xml)
                else -> call.respondText(exampleContentString)
            }
    }
    }

    authenticate("access_token") {
    get<Paths.getUser> {
        val principal = call.authentication.principal<UserIdPrincipal>()!!
        
        val exampleContentType = "application/json"
            val exampleContentString = """{
              "user_type" : "normal",
              "phone" : "phone",
              "organization_id" : "organization_id",
              "login_access" : true,
              "created_at" : "created_at",
              "id" : "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "created_by" : "046b6c7f-0b8a-43b9-b35d-6489e6daee91",
              "email" : "email",
              "username" : "username",
              "status" : "active"
            }"""
            
            when (exampleContentType) {
                "application/json" -> call.respond(gson.fromJson(exampleContentString, empty::class.java))
                "application/xml" -> call.respondText(exampleContentString, ContentType.Text.Xml)
                else -> call.respondText(exampleContentString)
            }
    }
    }

    authenticate("access_token") {
    patch<Paths.updateUser> {
        val principal = call.authentication.principal<UserIdPrincipal>()!!
        
        val exampleContentType = "application/json"
            val exampleContentString = """{
              "user_type" : "normal",
              "phone" : "phone",
              "organization_id" : "organization_id",
              "login_access" : true,
              "created_at" : "created_at",
              "id" : "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "created_by" : "046b6c7f-0b8a-43b9-b35d-6489e6daee91",
              "email" : "email",
              "username" : "username",
              "status" : "active"
            }"""
            
            when (exampleContentType) {
                "application/json" -> call.respond(gson.fromJson(exampleContentString, empty::class.java))
                "application/xml" -> call.respondText(exampleContentString, ContentType.Text.Xml)
                else -> call.respondText(exampleContentString)
            }
    }
    }

}
