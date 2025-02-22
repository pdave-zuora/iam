@file:Suppress("LongParameterList")

package com.hypto.iam.server.service

import com.google.gson.Gson
import com.hypto.iam.server.configs.AppConfig
import com.hypto.iam.server.db.repositories.CredentialsRepo
import com.hypto.iam.server.db.repositories.LinkUsersRepo
import com.hypto.iam.server.db.repositories.OrganizationRepo
import com.hypto.iam.server.db.repositories.PasscodeRepo
import com.hypto.iam.server.db.repositories.UserAuthRepo
import com.hypto.iam.server.db.repositories.UserRepo
import com.hypto.iam.server.db.tables.records.LinkUsersRecord
import com.hypto.iam.server.db.tables.records.UsersRecord
import com.hypto.iam.server.exceptions.EntityNotFoundException
import com.hypto.iam.server.exceptions.InternalException
import com.hypto.iam.server.extensions.PaginationContext
import com.hypto.iam.server.extensions.getEncodedEmail
import com.hypto.iam.server.extensions.toUTCOffset
import com.hypto.iam.server.extensions.toUserStatus
import com.hypto.iam.server.idp.IdentityGroup
import com.hypto.iam.server.idp.IdentityProvider
import com.hypto.iam.server.idp.PasswordCredentials
import com.hypto.iam.server.idp.RequestContext
import com.hypto.iam.server.idp.UserAlreadyExistException
import com.hypto.iam.server.models.BaseSuccessResponse
import com.hypto.iam.server.models.LinkUserRequest
import com.hypto.iam.server.models.LinkUserResponse
import com.hypto.iam.server.models.LinkUsersPaginatedResponse
import com.hypto.iam.server.models.TokenResponse
import com.hypto.iam.server.models.UpdateUserRequest
import com.hypto.iam.server.models.User
import com.hypto.iam.server.models.UserPaginatedResponse
import com.hypto.iam.server.models.VerifyEmailRequest
import com.hypto.iam.server.security.AuthenticationException
import com.hypto.iam.server.security.TokenCredential
import com.hypto.iam.server.security.TokenType
import com.hypto.iam.server.security.UserPrincipal
import com.hypto.iam.server.security.UsernamePasswordCredential
import com.hypto.iam.server.security.isIamJWT
import com.hypto.iam.server.service.TokenServiceImpl.Companion.ON_BEHALF_CLAIM
import com.hypto.iam.server.utils.HrnFactory
import com.hypto.iam.server.utils.IamResources
import com.hypto.iam.server.utils.IdGenerator
import com.hypto.iam.server.utils.ResourceHrn
import com.hypto.iam.server.validators.EMAIL_REGEX
import com.hypto.iam.server.validators.RequestAccessMetadata
import com.txman.TxMan
import io.ktor.server.plugins.BadRequestException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.LocalDateTime

const val USER_NOT_FOUND_ERROR_MESSAGE = "Unable to find user"
const val INVALID_ORG_ID_NAME = "Invalid organization id name."

@Suppress("LargeClass")
class UsersServiceImpl : KoinComponent, UsersService {
    private val principalPolicyService: PrincipalPolicyService by inject()
    private val hrnFactory: HrnFactory by inject()
    private val userRepo: UserRepo by inject()
    private val organizationRepo: OrganizationRepo by inject()
    private val identityProvider: IdentityProvider by inject()
    private val gson: Gson by inject()
    private val txMan: TxMan by inject()
    private val appConfig: AppConfig by inject()
    private val passcodeRepo: PasscodeRepo by inject()
    private val userAuthRepo: UserAuthRepo by inject()
    private val credentialRepo: CredentialsRepo by inject()
    private val userPrincipalService: UserPrincipalService by inject()
    private val linkUsersRepo: LinkUsersRepo by inject()
    private val tokenService: TokenService by inject()
    private val passcodeService: PasscodeService by inject()

    @Suppress("CyclomaticComplexMethod", "ComplexMethod")
    override suspend fun createUser(
        organizationId: String,
        subOrganizationName: String?,
        loginAccess: Boolean,
        username: String,
        preferredUsername: String?,
        name: String?,
        email: String?,
        phoneNumber: String?,
        password: String?,
        createdBy: String?,
        verified: Boolean,
        policies: List<String>?,
    ): User {
        if (email == null && subOrganizationName != null) {
            throw BadRequestException("Email is required for sub-organization users")
        }
        val uniquenesAcrossOrgs =
            if (subOrganizationName == null) {
                appConfig.app.uniqueUsersAcrossOrganizations
            } else {
                false
            }
        if (userRepo.existsByAliasUsername(
                preferredUsername,
                email,
                organizationId,
                subOrganizationName,
                uniquenesAcrossOrgs,
            )
        ) {
            throw UserAlreadyExistException(
                email?.let { "Email - $email" }.orEmpty() +
                    preferredUsername?.let { "Username - $preferredUsername" }.orEmpty() +
                    " already registered. Unable to create user",
            )
        }

        return txMan.wrap {
            // Note: We are using email sub-addressing to support same email login across multiple sub orgs
            // in different orgs.
            // Example - An email address x@y.com can be a user for multiple hypto sub-orgs for different org.
            // To support this model, we are using email sub-addressing where we will append the local part
            // (before '@') with base64 value of orgId. By this way, the email address and password credentials
            // will be uniquely stored in the identity provider and later used for authentication.
            if (password != null && loginAccess) {
                createUserInIdentityProvider(
                    username,
                    preferredUsername,
                    name,
                    email,
                    phoneNumber,
                    password,
                    organizationId,
                    subOrganizationName,
                    createdBy,
                    verified,
                )
            }
            val userHrn = ResourceHrn(organizationId, subOrganizationName, IamResources.USER, username)
            val userRecord =
                userRepo.insert(
                    UsersRecord().apply {
                        this.hrn = userHrn.toString()
                        this.organizationId = organizationId
                        this.email = email
                        this.status = User.Status.enabled.value
                        this.verified = verified
                        this.deleted = false
                        this.createdAt = LocalDateTime.now()
                        this.updatedAt = LocalDateTime.now()
                        this.preferredUsername = preferredUsername
                        this.loginAccess = loginAccess
                        this.name = name
                        this.subOrganizationName = subOrganizationName
                        this.createdBy = createdBy
                    },
                ) ?: throw InternalException("Unable to create user")

            if (!policies.isNullOrEmpty()) {
                principalPolicyService.attachPoliciesToUser(
                    ResourceHrn(userHrn.toString()),
                    policies.map { hrnFactory.getHrn(it) },
                )
            }
            if (password != null && email != null) {
                getEncodedEmail(organizationId, subOrganizationName, email).also { encodedEmail ->
                    passcodeRepo.deleteByEmailAndPurpose(encodedEmail, VerifyEmailRequest.Purpose.invite)
                }
            }
            getUser(userHrn, userRecord)
        }
    }

    @Suppress("ThrowsCount")
    private suspend fun createUserInIdentityProvider(
        username: String,
        preferredUsername: String?,
        name: String?,
        email: String?,
        phoneNumber: String?,
        password: String?,
        organizationId: String,
        subOrganizationName: String?,
        createdBy: String?,
        verified: Boolean,
    ) {
        val org =
            organizationRepo.findById(organizationId)
                ?: throw EntityNotFoundException("Invalid organization id")
        val encodedEmail =
            email?.let {
                getEncodedEmail(organizationId, subOrganizationName, email)
            } ?: throw BadRequestException("Email is required")

        val passwordCredentials =
            PasswordCredentials(
                username = username,
                preferredUsername = preferredUsername,
                name = name,
                email = encodedEmail,
                phoneNumber = phoneNumber ?: "",
                password = password ?: throw BadRequestException("Password is required"),
            )

        val identityGroup =
            if (org.metadata != null) {
                gson.fromJson(org.metadata.data(), IdentityGroup::class.java)
            } else {
                organizationRepo.update(
                    organizationId,
                    null,
                    null,
                    appConfig.cognito,
                )
                appConfig.cognito
            }

        identityProvider.createUser(
            RequestContext(
                organizationId = organizationId,
                subOrganizationName = subOrganizationName,
                requestedPrincipal = createdBy ?: "unknown user",
                verified,
            ),
            identityGroup,
            passwordCredentials,
        )
    }

    override suspend fun getUser(
        organizationId: String,
        subOrganizationName: String?,
        userName: String,
    ): User {
        organizationRepo.findById(organizationId)
            ?: throw EntityNotFoundException("Invalid organization id. Unable to get user")
        val userHrn = ResourceHrn(organizationId, subOrganizationName, IamResources.USER, userName)
        val userRecord =
            userRepo.findByHrn(userHrn.toString())
                ?: throw EntityNotFoundException(USER_NOT_FOUND_ERROR_MESSAGE)
        return getUser(userHrn, userRecord)
    }

    override suspend fun getUser(userHrn: ResourceHrn): User {
        val userName = userHrn.resourceInstance ?: throw BadRequestException("Invalid user HRN")
        return getUser(userHrn.organization, userHrn.subOrganization, userName)
    }

    override suspend fun getUserByEmail(
        organizationId: String?,
        subOrganizationName: String?,
        email: String,
    ): User {
        val userRecord =
            if (subOrganizationName.isNullOrEmpty() && appConfig.app.uniqueUsersAcrossOrganizations) {
                val user =
                    userRepo.findByEmail(email)
                        ?: throw EntityNotFoundException("User with email - $email not found")
                organizationRepo.findById(user.organizationId)
                    ?: throw EntityNotFoundException("$INVALID_ORG_ID_NAME $USER_NOT_FOUND_ERROR_MESSAGE")
                user
            } else {
                organizationId ?: throw EntityNotFoundException("Organization id is required")
                userRepo.findByEmail(email, organizationId, subOrganizationName)
            } ?: throw EntityNotFoundException("User with email - $email not found")

        return getUser(ResourceHrn(userRecord.hrn), userRecord)
    }

    override suspend fun changeUserPassword(
        organizationId: String,
        subOrganizationName: String?,
        userName: String,
        oldPassword: String,
        newPassword: String,
    ): BaseSuccessResponse {
        val org =
            organizationRepo.findById(organizationId)
                ?: throw EntityNotFoundException("Invalid organization id name. Unable to authenticate user")

        val userHrn = ResourceHrn(organizationId, subOrganizationName ?: "", IamResources.USER, userName)
        val userRecord =
            userRepo.findByHrn(userHrn.toString())
                ?: throw EntityNotFoundException(USER_NOT_FOUND_ERROR_MESSAGE)
        if (userRecord.loginAccess != true) {
            throw BadRequestException("User - $userName does not have login access")
        }

        if (userAuthRepo.fetchByUserHrnAndProviderName(userHrn.toString(), TokenServiceImpl.ISSUER) == null) {
            throw BadRequestException("User does not have password access")
        }

        val identityGroup = gson.fromJson(org.metadata.data(), IdentityGroup::class.java)
        identityProvider.authenticate(identityGroup, userName, oldPassword)
        identityProvider.setUserPassword(identityGroup, userName, newPassword)
        return BaseSuccessResponse(true)
    }

    override suspend fun setUserPassword(
        organizationId: String,
        subOrganizationName: String?,
        user: User,
        password: String,
    ): BaseSuccessResponse {
        val org =
            organizationRepo.findById(organizationId)
                ?: throw EntityNotFoundException("Invalid organization id name. Unable to set user password")

        val userHrn = ResourceHrn(user.hrn)
        val userRecord =
            userRepo.findByHrn(userHrn.toString())
                ?: throw EntityNotFoundException(USER_NOT_FOUND_ERROR_MESSAGE)
        if (userRecord.loginAccess != true) {
            throw BadRequestException("User - ${userHrn.resourceInstance} does not have login access")
        }

        if (org.metadata != null) {
            val identityGroup = gson.fromJson(org.metadata.data(), IdentityGroup::class.java)
            identityProvider.setUserPassword(identityGroup, user.username, password)
        } else {
            txMan.wrap {
                organizationRepo.update(
                    organizationId,
                    null,
                    null,
                    appConfig.cognito,
                )
                createUserInIdentityProvider(
                    user.username,
                    user.preferredUsername,
                    user.name,
                    user.email,
                    user.phone,
                    password,
                    organizationId,
                    subOrganizationName,
                    user.createdBy,
                    user.verified,
                )
                userAuthRepo.create(
                    user.hrn,
                    TokenServiceImpl.ISSUER,
                    authMetadata = null,
                )
            }
        }

        passcodeRepo.deleteByEmailAndPurpose(user.email!!, VerifyEmailRequest.Purpose.reset)
        return BaseSuccessResponse(true)
    }

    override suspend fun createUserPassword(
        organizationId: String,
        subOrganizationName: String?,
        userId: String,
        password: String,
    ): User {
        val user = getUser(organizationId, subOrganizationName, userId)
        organizationRepo.findById(organizationId)
            ?: throw EntityNotFoundException("Invalid organization id")
        if (userAuthRepo.fetchByUserHrnAndProviderName(user.hrn, TokenServiceImpl.ISSUER) != null) {
            throw BadRequestException("Organization already has password access")
        }
        createUserInIdentityProvider(
            user.username,
            user.preferredUsername,
            user.name,
            user.email,
            user.phone,
            password,
            organizationId,
            subOrganizationName,
            user.createdBy,
            true,
        )
        txMan.wrap {
            userAuthRepo.create(
                hrn = user.hrn,
                providerName = TokenServiceImpl.ISSUER,
                authMetadata = null,
            )
            userRepo.update(
                hrn = user.hrn,
                verified = true,
                loginAccess = true,
            )
            passcodeRepo.deleteByEmailAndPurpose(user.email!!, VerifyEmailRequest.Purpose.invite)
        }
        return getUser(organizationId, subOrganizationName, userId)
    }

    override suspend fun listUsers(
        organizationId: String,
        subOrganizationName: String?,
        paginationContext: PaginationContext,
    ): UserPaginatedResponse {
        val org =
            organizationRepo.findById(organizationId)
                ?: throw EntityNotFoundException("$INVALID_ORG_ID_NAME $USER_NOT_FOUND_ERROR_MESSAGE")

        val users =
            userRepo.fetchUsers(organizationId, subOrganizationName, paginationContext).map { user ->
                getUser(ResourceHrn(user.hrn), user)
            }.toList()

        val newContext = PaginationContext.from(users.lastOrNull()?.hrn, paginationContext)
        return UserPaginatedResponse(
            users,
            newContext.nextToken,
            newContext.toOptions(),
        )
    }

    private fun getUser(
        userHrn: ResourceHrn,
        user: UsersRecord,
    ) = User(
        hrn = userHrn.toString(),
        username = userHrn.resourceInstance.toString(),
        preferredUsername = user.preferredUsername,
        name = user.name ?: "",
        organizationId = userHrn.organization,
        email = user.email,
        status = if (user.status == User.Status.enabled.value) User.Status.enabled else User.Status.disabled,
        verified = user.verified,
        createdBy = user.createdBy ?: "",
        createdAt = user.createdAt.toUTCOffset(),
        loginAccess = user.loginAccess ?: false,
    )

    override suspend fun updateUser(
        organizationId: String,
        subOrganizationName: String?,
        userName: String,
        name: String?,
        phone: String?,
        status: UpdateUserRequest.Status?,
        verified: Boolean?,
    ): User {
        val org =
            organizationRepo.findById(organizationId)
                ?: throw EntityNotFoundException("Invalid organization id name. Unable to get user")
        val userHrn = ResourceHrn(organizationId, subOrganizationName, IamResources.USER, userName)
        val userRecord =
            userRepo.findByHrn(userHrn.toString())
                ?: throw EntityNotFoundException("User not found")

        val userStatus = status?.toUserStatus()
        if (userRecord.loginAccess == true &&
            org.metadata != null &&
            userAuthRepo.fetchByUserHrnAndProviderName(userHrn.toString(), TokenServiceImpl.ISSUER) != null
        ) {
            val identityGroup = gson.fromJson(org.metadata.data(), IdentityGroup::class.java)

            identityProvider.updateUser(
                identityGroup = identityGroup,
                name = name,
                userName = userName,
                phone = phone,
                status = userStatus,
                verified = verified,
            )
        }
        val updatedUserRecord =
            userRepo.update(
                hrn = userHrn.toString(),
                status = userStatus,
                verified = verified,
                name = name,
            ) ?: throw EntityNotFoundException("User not found")

        return getUser(userHrn, updatedUserRecord)
    }

    override suspend fun deleteUser(
        organizationId: String,
        subOrganizationName: String?,
        userName: String,
    ): BaseSuccessResponse {
        val org =
            organizationRepo.findById(organizationId)
                ?: throw EntityNotFoundException("Invalid organization id name. Unable to delete user")
        val userHrn = ResourceHrn(organizationId, subOrganizationName, IamResources.USER, userName)
        if (org.rootUserHrn == userHrn.toString()) {
            throw BadRequestException("Cannot delete Root User")
        }

        val userRecord =
            userRepo.findByHrn(userHrn.toString())
                ?: throw EntityNotFoundException("User not found")

        if (userRecord.loginAccess == true &&
            org.metadata != null &&
            userAuthRepo.fetchByUserHrnAndProviderName(userHrn.toString(), TokenServiceImpl.ISSUER) != null
        ) {
            val identityGroup = gson.fromJson(org.metadata.data(), IdentityGroup::class.java)
            identityProvider.deleteUser(identityGroup, userName)
        }
        txMan.wrap {
            credentialRepo.updateStatusForUser(userHrn.toString())
            userRepo.delete(userHrn.toString())
        }

        return BaseSuccessResponse(true)
    }

    override suspend fun authenticate(
        organizationId: String,
        subOrganizationName: String?,
        email: String,
        password: String,
    ): User {
        val org =
            organizationRepo.findById(organizationId)
                ?: throw EntityNotFoundException("Invalid organization id name. Unable to authenticate user")

        val userRecord =
            userRepo.findByEmail(email, organizationId, subOrganizationName)
                ?: throw EntityNotFoundException("User not found")
        if (userRecord.loginAccess != true) {
            throw BadRequestException("User - $email does not have login access")
        }

        if (userAuthRepo.fetchByUserHrnAndProviderName(userRecord.hrn, TokenServiceImpl.ISSUER) == null) {
            throw AuthenticationException("User - $email does not have password access")
        }

        val identityGroup = gson.fromJson(org.metadata.data(), IdentityGroup::class.java)
        val encodedEmail = getEncodedEmail(organizationId, subOrganizationName, email)
        identityProvider.authenticate(identityGroup, encodedEmail, password)

        return getUser(ResourceHrn(userRecord.hrn), userRecord)
    }

    override suspend fun authenticate(
        organizationId: String?,
        username: String,
        password: String,
    ): User {
        val userRecord =
            if (EMAIL_REGEX.toRegex().matches(username)) {
                userRepo.findByEmail(username)
            } else {
                userRepo.findByPreferredUsername(username)
            } ?: throw AuthenticationException("Invalid username and password combination")

        val org =
            organizationRepo.findById(userRecord.organizationId)
                ?: throw InternalException("Internal error while trying to authenticate the identity.")

        if (userRecord.loginAccess == null) {
            throw BadRequestException("User - $username does not have login access")
        }

        if (userAuthRepo.fetchByUserHrnAndProviderName(userRecord.hrn, TokenServiceImpl.ISSUER) == null) {
            throw AuthenticationException("User - $username does not have password access")
        }

        val identityGroup = gson.fromJson(org.metadata.data(), IdentityGroup::class.java)
        val user = identityProvider.authenticate(identityGroup, username, password)
        val userHrn = ResourceHrn(userRecord.organizationId, "", IamResources.USER, user.username)
        return getUser(userHrn, userRecord)
    }

    override suspend fun linkUser(
        principal: UserPrincipal,
        request: LinkUserRequest,
    ): TokenResponse {
        val (inviterHrn, inviteeHrn) =
            when (request.type) {
                LinkUserRequest.Type.PASSCODE -> {
                    val passcode =
                        passcodeRepo.getValidPasscodeById(
                            request.passcodeConfig!!.passcode,
                            VerifyEmailRequest.Purpose.link_user,
                        ) ?: throw AuthenticationException("Invalid passcode")
                    val user = getUser(principal.hrn as ResourceHrn)
                    require(passcode.email == user.email) { "Email in passcode does not match with your email" }
                    val metadata = RequestAccessMetadata(passcodeService.decryptMetadata(passcode.metadata))
                    metadata.inviterUserHrn to principal.hrn
                }
                LinkUserRequest.Type.USERNAME_PASSWORD -> {
                    val config = request.usernamePasswordConfig!!
                    val inviteeHrn =
                        if (config.organizationId != null) {
                            userPrincipalService.getUserPrincipalByCredentials(
                                organizationId = config.organizationId,
                                subOrganizationId = config.subOrganizationId,
                                userName = config.email.lowercase(),
                                password = config.password,
                            ).hrn
                        } else {
                            require(appConfig.app.uniqueUsersAcrossOrganizations) {
                                "Email not unique across organizations. Please use passcode or token credential type"
                            }
                            userPrincipalService.getUserPrincipalByCredentials(
                                UsernamePasswordCredential(config.email.lowercase(), config.password),
                            ).hrn
                        }
                    principal.hrnStr to inviteeHrn
                }
                LinkUserRequest.Type.TOKEN_CREDENTIAL -> {
                    val token = request.tokenCredentialConfig!!.token
                    val inviteeHrn =
                        if (isIamJWT(token)) {
                            userPrincipalService.getUserPrincipalByJwtToken(TokenCredential(token, TokenType.JWT)).hrn
                        } else {
                            userPrincipalService.getUserPrincipalByRefreshToken(TokenCredential(token, TokenType.CREDENTIAL))?.hrn
                                ?: throw BadRequestException("Invalid token credentials")
                        }
                    principal.hrnStr to inviteeHrn
                }
            }
        require(inviterHrn != inviteeHrn.toString()) { "Leader and subordinate can't be the same user" }
        val now = LocalDateTime.now()
        val record =
            LinkUsersRecord().apply {
                id = IdGenerator.timeBasedRandomId(length = 20L)
                leaderUserHrn = inviterHrn
                subordinateUserHrn = inviteeHrn.toString()
                createdAt = now
                updatedAt = now
            }
        linkUsersRepo.store(record)
        return tokenService.generateJwtToken(inviteeHrn, principal.hrn)
    }

    override suspend fun listUserLinks(
        principal: UserPrincipal,
        context: PaginationContext,
    ): LinkUsersPaginatedResponse {
        return when (context.additionalParams?.get("role") as String?) {
            "LEADER" -> listLeaderUsers(principal, context)
            "SUBORDINATE" -> listSubordinateUsers(principal, context)
            else -> throw IllegalArgumentException("Invalid value found for query param role")
        }
    }

    private suspend fun listLeaderUsers(
        principal: UserPrincipal,
        context: PaginationContext,
    ): LinkUsersPaginatedResponse {
        val linkUserRecords = linkUsersRepo.fetchLeaderUsers(principal.hrnStr, context)
        val userRecordsMap = userRepo.findByHrns(linkUserRecords.keys.toList())
        val newContext = PaginationContext.from(linkUserRecords.entries.lastOrNull()?.value?.leaderUserHrn, context)
        return LinkUsersPaginatedResponse(
            data =
                userRecordsMap.map {
                    val linkUserRecord = linkUserRecords[it.key]!!
                    val userHrn = ResourceHrn(it.key)
                    LinkUserResponse(
                        id = linkUserRecord.id!!,
                        name = it.value.name ?: "",
                        organizationId = userHrn.organization,
                        email = it.value.email,
                    )
                },
            nextToken = newContext.nextToken,
            context = newContext.toOptions(),
        )
    }

    private suspend fun listSubordinateUsers(
        principal: UserPrincipal,
        context: PaginationContext,
    ): LinkUsersPaginatedResponse {
        val linkUserRecords = linkUsersRepo.fetchSubordinateUsers(principal.hrnStr, context)
        val userRecordsMap = userRepo.findByHrns(linkUserRecords.keys.toList())
        val newContext = PaginationContext.from(linkUserRecords.entries.lastOrNull()?.value?.subordinateUserHrn, context)
        return LinkUsersPaginatedResponse(
            data =
                userRecordsMap.map {
                    val linkUserRecord = linkUserRecords[it.key]!!
                    val userHrn = ResourceHrn(it.key)
                    LinkUserResponse(
                        id = linkUserRecord.id!!,
                        name = it.value.name ?: "",
                        organizationId = userHrn.organization,
                        email = it.value.email,
                    )
                },
            nextToken = newContext.nextToken,
            context = newContext.toOptions(),
        )
    }

    override suspend fun switchUser(
        principal: UserPrincipal,
        linkId: String,
    ): TokenResponse {
        val leaderUserHrn = principal.claims?.get(ON_BEHALF_CLAIM) as String? ?: principal.hrnStr
        val record =
            linkUsersRepo.getById(linkId)
                ?: throw EntityNotFoundException("Invalid user link id")
        require(record.leaderUserHrn == leaderUserHrn) {
            "User doesn't have permission to switch"
        }
        return if (record.leaderUserHrn == principal.hrnStr) {
            tokenService.generateJwtToken(ResourceHrn(record.subordinateUserHrn), principal.hrn)
        } else {
            tokenService.generateJwtToken(ResourceHrn(record.leaderUserHrn))
        }
    }

    override suspend fun deleteUserLink(
        principal: UserPrincipal,
        linkId: String,
    ): BaseSuccessResponse {
        val record =
            linkUsersRepo.getById(linkId)
                ?: throw EntityNotFoundException("Invalid user link id")
        val userHrn = principal.hrnStr
        require(record.leaderUserHrn == userHrn || record.subordinateUserHrn == userHrn) {
            "Only the leader or the subordinated user can delete the link"
        }
        return BaseSuccessResponse(linkUsersRepo.deleteById(linkId))
    }
}

/**
 * Service which holds logic related to User operations
 */
@Suppress("TooManyFunctions")
interface UsersService {
    suspend fun createUser(
        organizationId: String,
        subOrganizationName: String?,
        loginAccess: Boolean,
        username: String,
        preferredUsername: String?,
        name: String?,
        email: String?,
        phoneNumber: String?,
        password: String?,
        createdBy: String?,
        verified: Boolean,
        policies: List<String>? = null,
    ): User

    suspend fun getUser(
        organizationId: String,
        subOrganizationName: String?,
        userName: String,
    ): User

    suspend fun getUser(userHrn: ResourceHrn): User

    suspend fun listUsers(
        organizationId: String,
        subOrganizationName: String?,
        paginationContext: PaginationContext,
    ): UserPaginatedResponse

    suspend fun updateUser(
        organizationId: String,
        subOrganizationName: String?,
        userName: String,
        name: String?,
        phone: String?,
        status: UpdateUserRequest.Status? = null,
        verified: Boolean?,
    ): User

    suspend fun deleteUser(
        organizationId: String,
        subOrganizationName: String?,
        userName: String,
    ): BaseSuccessResponse

    suspend fun authenticate(
        organizationId: String,
        subOrganizationName: String?,
        email: String,
        password: String,
    ): User

    suspend fun authenticate(
        organizationId: String?,
        username: String,
        password: String,
    ): User

    suspend fun getUserByEmail(
        organizationId: String?,
        subOrganizationName: String?,
        email: String,
    ): User

    suspend fun setUserPassword(
        organizationId: String,
        subOrganizationName: String?,
        user: User,
        password: String,
    ): BaseSuccessResponse

    suspend fun createUserPassword(
        organizationId: String,
        subOrganizationName: String?,
        userId: String,
        password: String,
    ): User

    suspend fun changeUserPassword(
        organizationId: String,
        subOrganizationName: String?,
        userName: String,
        oldPassword: String,
        newPassword: String,
    ): BaseSuccessResponse

    suspend fun linkUser(
        principal: UserPrincipal,
        request: LinkUserRequest,
    ): TokenResponse

    suspend fun listUserLinks(
        principal: UserPrincipal,
        context: PaginationContext,
    ): LinkUsersPaginatedResponse

    suspend fun switchUser(
        principal: UserPrincipal,
        linkId: String,
    ): TokenResponse

    suspend fun deleteUserLink(
        principal: UserPrincipal,
        linkId: String,
    ): BaseSuccessResponse
}
