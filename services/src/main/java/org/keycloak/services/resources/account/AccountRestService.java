/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.services.resources.account;

import org.jboss.resteasy.annotations.cache.NoCache;
import org.jboss.resteasy.spi.HttpRequest;
import org.keycloak.common.ClientConnection;
import org.keycloak.common.enums.AccountRestApiVersion;
import org.keycloak.common.Profile;
import org.keycloak.common.util.StringPropertyReplacer;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventStoreProvider;
import org.keycloak.events.EventType;
import org.keycloak.models.AccountRoles;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserConsentModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.representations.account.ClientRepresentation;
import org.keycloak.representations.account.ConsentRepresentation;
import org.keycloak.representations.account.ConsentScopeRepresentation;
import org.keycloak.representations.account.UserRepresentation;
import org.keycloak.services.ErrorResponse;
import org.keycloak.services.managers.Auth;
import org.keycloak.services.managers.UserSessionManager;
import org.keycloak.services.messages.Messages;
import org.keycloak.services.resources.Cors;
import org.keycloak.services.resources.account.resources.ResourcesService;
import org.keycloak.services.util.ResolveRelative;
import org.keycloak.storage.ReadOnlyException;
import org.keycloak.theme.Theme;
import org.keycloak.userprofile.LegacyUserProfileProviderFactory;
import org.keycloak.userprofile.UserProfile;
import org.keycloak.userprofile.UserProfileProvider;
import org.keycloak.userprofile.utils.UserUpdateHelper;
import org.keycloak.userprofile.profile.representations.AccountUserRepresentationUserProfile;
import org.keycloak.userprofile.profile.DefaultUserProfileContext;
import org.keycloak.userprofile.validation.UserProfileValidationResult;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class AccountRestService {

    @Context
    private HttpRequest request;
    @Context
    protected HttpHeaders headers;
    @Context
    protected ClientConnection clientConnection;

    private final KeycloakSession session;
    private final ClientModel client;
    private final EventBuilder event;
    private EventStoreProvider eventStore;
    private Auth auth;
    
    private final RealmModel realm;
    private final UserModel user;
    private final Locale locale;
    private final AccountRestApiVersion version;

    public AccountRestService(KeycloakSession session, Auth auth, ClientModel client, EventBuilder event, AccountRestApiVersion version) {
        this.session = session;
        this.auth = auth;
        this.realm = auth.getRealm();
        this.user = auth.getUser();
        this.client = client;
        this.event = event;
        this.locale = session.getContext().resolveLocale(user);
        this.version = version;
    }
    
    public void init() {
        eventStore = session.getProvider(EventStoreProvider.class);
    }

    /**
     * CORS preflight
     *
     * @return
     */
    @Path("/")
    @OPTIONS
    @NoCache
    public Response preflight() {
        return Cors.add(request, Response.ok()).auth().preflight().build();
    }

    /**
     * Get account information.
     *
     * @return
     */
    @Path("/")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @NoCache
    public Response account() {
        auth.requireOneOf(AccountRoles.MANAGE_ACCOUNT, AccountRoles.VIEW_PROFILE);

        UserModel user = auth.getUser();

        UserRepresentation rep = new UserRepresentation();
        rep.setUsername(user.getUsername());
        rep.setFirstName(user.getFirstName());
        rep.setLastName(user.getLastName());
        rep.setEmail(user.getEmail());
        rep.setEmailVerified(user.isEmailVerified());
        rep.setEmailVerified(user.isEmailVerified());
        Map<String, List<String>> attributes = user.getAttributes();
        Map<String, List<String>> copiedAttributes = new HashMap<>(attributes);
        copiedAttributes.remove(UserModel.FIRST_NAME);
        copiedAttributes.remove(UserModel.LAST_NAME);
        copiedAttributes.remove(UserModel.EMAIL);
        copiedAttributes.remove(UserModel.USERNAME);
        rep.setAttributes(copiedAttributes);

        return Cors.add(request, Response.ok(rep)).auth().allowedOrigins(auth.getToken()).build();
    }

    @Path("/")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @NoCache
    public Response updateAccount(UserRepresentation rep) {
        auth.require(AccountRoles.MANAGE_ACCOUNT);

        event.event(EventType.UPDATE_PROFILE).client(auth.getClient()).user(auth.getUser());

        UserProfile updatedUser = new AccountUserRepresentationUserProfile(rep);
        UserProfileProvider profileProvider = session.getProvider(UserProfileProvider.class, LegacyUserProfileProviderFactory.PROVIDER_ID);
        UserProfileValidationResult result = profileProvider.validate(DefaultUserProfileContext.forAccountService(user), updatedUser);

        if (result.hasFailureOfErrorType(Messages.READ_ONLY_USERNAME))
            return ErrorResponse.error(Messages.READ_ONLY_USERNAME, Response.Status.BAD_REQUEST);
        if (result.hasFailureOfErrorType(Messages.USERNAME_EXISTS))
            return ErrorResponse.exists(Messages.USERNAME_EXISTS);
        if (result.hasFailureOfErrorType(Messages.EMAIL_EXISTS))
            return ErrorResponse.exists(Messages.EMAIL_EXISTS);

        try {
            UserUpdateHelper.updateAccount(realm, user, updatedUser);
            event.success();

            return Cors.add(request, Response.noContent()).auth().allowedOrigins(auth.getToken()).build();
        } catch (ReadOnlyException e) {
            return ErrorResponse.error(Messages.READ_ONLY_USER, Response.Status.BAD_REQUEST);
        }
    }

    /**
     * Get session information.
     *
     * @return
     */
    @Path("/sessions")
    public SessionResource sessions() {
        checkAccountApiEnabled();
        auth.requireOneOf(AccountRoles.MANAGE_ACCOUNT, AccountRoles.VIEW_PROFILE);
        return new SessionResource(session, auth, request);
    }

    @Path("/credentials")
    public AccountCredentialResource credentials() {
        checkAccountApiEnabled();
        return new AccountCredentialResource(session, user, auth);
    }

    @Path("/resources")
    public ResourcesService resources() {
        checkAccountApiEnabled();
        auth.requireOneOf(AccountRoles.MANAGE_ACCOUNT, AccountRoles.VIEW_PROFILE);
        return new ResourcesService(session, user, auth, request);
    }

    // TODO Federated identities

    private ClientRepresentation modelToRepresentation(ClientModel model, List<String> inUseClients, List<String> offlineClients, Map<String, UserConsentModel> consents) {
        ClientRepresentation representation = new ClientRepresentation();
        representation.setClientId(model.getClientId());
        representation.setClientName(StringPropertyReplacer.replaceProperties(model.getName(), getProperties()));
        representation.setDescription(model.getDescription());
        representation.setUserConsentRequired(model.isConsentRequired());
        representation.setInUse(inUseClients.contains(model.getClientId()));
        representation.setOfflineAccess(offlineClients.contains(model.getClientId()));
        representation.setRootUrl(model.getRootUrl());
        representation.setBaseUrl(model.getBaseUrl());
        representation.setEffectiveUrl(ResolveRelative.resolveRelativeUri(session, model.getRootUrl(), model.getBaseUrl()));
        UserConsentModel consentModel = consents.get(model.getClientId());
        if(consentModel != null) {
            representation.setConsent(modelToRepresentation(consentModel));
        }
        return representation;
    }

    private ConsentRepresentation modelToRepresentation(UserConsentModel model) {
        List<ConsentScopeRepresentation> grantedScopes = model.getGrantedClientScopes().stream()
                .map(m -> new ConsentScopeRepresentation(m.getId(), m.getName(), StringPropertyReplacer.replaceProperties(m.getConsentScreenText(), getProperties())))
                .collect(Collectors.toList());
        return new ConsentRepresentation(grantedScopes, model.getCreatedDate(), model.getLastUpdatedDate());
    }

    private Properties getProperties() {
        try {
            return session.theme().getTheme(Theme.Type.ACCOUNT).getMessages(locale);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Returns the consent for the client with the given client id.
     *
     * @param clientId client id to return the consent for
     * @return consent of the client
     */
    @Path("/applications/{clientId}/consent")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConsent(final @PathParam("clientId") String clientId) {
        checkAccountApiEnabled();
        auth.requireOneOf(AccountRoles.MANAGE_ACCOUNT, AccountRoles.VIEW_CONSENT, AccountRoles.MANAGE_CONSENT);

        ClientModel client = realm.getClientByClientId(clientId);
        if (client == null) {
            return Cors.add(request, Response.status(Response.Status.NOT_FOUND).entity("No client with clientId: " + clientId + " found.")).build();
        }

        UserConsentModel consent = session.users().getConsentByClient(realm, user.getId(), client.getId());
        if (consent == null) {
            return Cors.add(request, Response.noContent()).build();
        }

        return Cors.add(request, Response.ok(modelToRepresentation(consent))).build();
    }

    /**
     * Deletes the consent for the client with the given client id.
     *
     * @param clientId client id to delete a consent for
     * @return returns 202 if deleted
     */
    @Path("/applications/{clientId}/consent")
    @DELETE
    public Response revokeConsent(final @PathParam("clientId") String clientId) {
        checkAccountApiEnabled();
        auth.requireOneOf(AccountRoles.MANAGE_ACCOUNT, AccountRoles.MANAGE_CONSENT);

        event.event(EventType.REVOKE_GRANT);
        ClientModel client = realm.getClientByClientId(clientId);
        if (client == null) {
            event.event(EventType.REVOKE_GRANT_ERROR);
            String msg = String.format("No client with clientId: %s found.", clientId);
            event.error(msg);
            return Cors.add(request, Response.status(Response.Status.NOT_FOUND).entity(msg)).build();
        }

        session.users().revokeConsentForClient(realm, user.getId(), client.getId());
        new UserSessionManager(session).revokeOfflineToken(user, client);
        event.success();

        return Cors.add(request, Response.noContent()).build();
    }

    /**
     * Creates or updates the consent of the given, requested consent for
     * the client with the given client id. Returns the appropriate REST response.
     *
     * @param clientId client id to set a consent for
     * @param consent  requested consent for the client
     * @return the created or updated consent
     */
    @Path("/applications/{clientId}/consent")
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response grantConsent(final @PathParam("clientId") String clientId,
                                 final ConsentRepresentation consent) {
        return upsert(clientId, consent);
    }

    /**
     * Creates or updates the consent of the given, requested consent for
     * the client with the given client id. Returns the appropriate REST response.
     *
     * @param clientId client id to set a consent for
     * @param consent  requested consent for the client
     * @return the created or updated consent
     */
    @Path("/applications/{clientId}/consent")
    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateConsent(final @PathParam("clientId") String clientId,
                                  final ConsentRepresentation consent) {
        return upsert(clientId, consent);
    }

    /**
     * Creates or updates the consent of the given, requested consent for
     * the client with the given client id. Returns the appropriate REST response.
     *
     * @param clientId client id to set a consent for
     * @param consent  requested consent for the client
     * @return response to return to the caller
     */
    private Response upsert(String clientId, ConsentRepresentation consent) {
        checkAccountApiEnabled();
        auth.requireOneOf(AccountRoles.MANAGE_ACCOUNT, AccountRoles.MANAGE_CONSENT);

        event.event(EventType.GRANT_CONSENT);
        ClientModel client = realm.getClientByClientId(clientId);
        if (client == null) {
            event.event(EventType.GRANT_CONSENT_ERROR);
            String msg = String.format("No client with clientId: %s found.", clientId);
            event.error(msg);
            return Cors.add(request, Response.status(Response.Status.NOT_FOUND).entity(msg)).build();
        }

        try {
            UserConsentModel grantedConsent = createConsent(client, consent);
            if (session.users().getConsentByClient(realm, user.getId(), client.getId()) == null) {
                session.users().addConsent(realm, user.getId(), grantedConsent);
            } else {
                session.users().updateConsent(realm, user.getId(), grantedConsent);
            }
            event.success();
            grantedConsent = session.users().getConsentByClient(realm, user.getId(), client.getId());
            return Cors.add(request, Response.ok(modelToRepresentation(grantedConsent))).build();
        } catch (IllegalArgumentException e) {
            return Cors.add(request, Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage())).build();
        }
    }

    /**
     * Create a new consent model object from the requested consent object
     * for the given client model.
     *
     * @param client    client to create a consent for
     * @param requested list of client scopes that the new consent should contain
     * @return newly created consent model
     * @throws IllegalArgumentException throws an exception if the scope id is not available
     */
    private UserConsentModel createConsent(ClientModel client, ConsentRepresentation requested) throws IllegalArgumentException {
        UserConsentModel consent = new UserConsentModel(client);
        Map<String, ClientScopeModel> availableGrants = realm.getClientScopes().stream().collect(Collectors.toMap(ClientScopeModel::getId, s -> s));

        if (client.isConsentRequired()) {
            availableGrants.put(client.getId(), client);
        }

        for (ConsentScopeRepresentation scopeRepresentation : requested.getGrantedScopes()) {
            ClientScopeModel scopeModel = availableGrants.get(scopeRepresentation.getId());
            if (scopeModel == null) {
                String msg = String.format("Scope id %s does not exist for client %s.", scopeRepresentation, consent.getClient().getName());
                event.error(msg);
                throw new IllegalArgumentException(msg);
            } else {
                consent.addGrantedClientScope(scopeModel);
            }
        }
        return consent;
    }
    
    @Path("/linked-accounts")
    public LinkedAccountsResource linkedAccounts() {
        return new LinkedAccountsResource(session, request, client, auth, event, user);
    }

    @Path("/applications")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @NoCache
    public Response applications(@QueryParam("name") String name) {
        checkAccountApiEnabled();
        auth.requireOneOf(AccountRoles.MANAGE_ACCOUNT, AccountRoles.VIEW_APPLICATIONS);

        Set<ClientModel> clients = new HashSet<ClientModel>();
        List<String> inUseClients = new LinkedList<String>();
        List<UserSessionModel> sessions = session.sessions().getUserSessions(realm, user);
        for(UserSessionModel s : sessions) {
            for (AuthenticatedClientSessionModel a : s.getAuthenticatedClientSessions().values()) {
                ClientModel client = a.getClient();
                clients.add(client);
                inUseClients.add(client.getClientId());
            }
        }

        List<String> offlineClients = new LinkedList<String>();
        List<UserSessionModel> offlineSessions = session.sessions().getOfflineUserSessions(realm, user);
        for(UserSessionModel s : offlineSessions) {
            for(AuthenticatedClientSessionModel a : s.getAuthenticatedClientSessions().values()) {
                ClientModel client = a.getClient();
                clients.add(client);
                offlineClients.add(client.getClientId());
            }
        }

        Map<String, UserConsentModel> consentModels = new HashMap<String, UserConsentModel>();
        List<UserConsentModel> consents = session.users().getConsents(realm, user.getId());
        for (UserConsentModel consent : consents) {
            ClientModel client = consent.getClient();
            clients.add(client);
            consentModels.put(client.getClientId(), consent);
        }

        realm.getAlwaysDisplayInConsoleClientsStream().forEach(clients::add);

        List<ClientRepresentation> apps = new LinkedList<ClientRepresentation>();
        for (ClientModel client : clients) {
            if (client.isBearerOnly() || client.getBaseUrl() == null || client.getBaseUrl().isEmpty()) {
                continue;
            }
            else if (matches(client, name)) {
                apps.add(modelToRepresentation(client, inUseClients, offlineClients, consentModels));
            }
        }

        return Cors.add(request, Response.ok(apps)).auth().allowedOrigins(auth.getToken()).build();
    }

    private boolean matches(ClientModel client, String name) {
        if(name == null)
            return true;
        else if(client.getName() == null)
            return false;
        else
            return client.getName().toLowerCase().contains(name.toLowerCase());
    }

    // TODO Logs
    
    private static void checkAccountApiEnabled() {
        if (!Profile.isFeatureEnabled(Profile.Feature.ACCOUNT_API)) {
            throw new NotFoundException();
        }
    }
}
