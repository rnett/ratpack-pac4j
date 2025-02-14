/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.pac4j.internal;

import static ratpack.util.Exceptions.uncheck;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Optional;
import org.pac4j.core.client.Client;
import org.pac4j.core.client.Clients;
import org.pac4j.core.exception.TechnicalException;
import org.pac4j.core.exception.http.HttpAction;
import org.pac4j.core.profile.UserProfile;
import org.pac4j.core.util.Pac4jConstants;
import ratpack.exec.Blocking;
import ratpack.exec.Promise;
import ratpack.handling.Context;
import ratpack.handling.Handler;
import ratpack.pac4j.RatpackPac4j;
import ratpack.path.PathBinding;
import ratpack.server.PublicAddress;
import ratpack.session.SessionData;
import ratpack.util.Types;

public class Pac4jAuthenticator implements Handler {

  private final String path;
  private final RatpackPac4j.ClientsProvider clientsProvider;

  public Pac4jAuthenticator(String path, RatpackPac4j.ClientsProvider clientsProvider) {
    this.path = path;
    this.clientsProvider = clientsProvider;
  }

  @Override
  public void handle(Context ctx) throws Exception {
    PathBinding pathBinding = ctx.getPathBinding();
    String pastBinding = pathBinding.getPastBinding();
    if (pastBinding.equals(path)) {
      RatpackWebContext.from(ctx, true).flatMap(webContext -> {
        SessionData sessionData = ((RatpackSessionStore) webContext.getSessionStore()).getSessionData();
        return createClients(ctx, pathBinding).map(clients -> {
              final String clientName = webContext.getRequestParameter(Pac4jConstants.DEFAULT_CLIENT_NAME_PARAMETER).get();
              return clients.findClient(clientName)
                  .orElseThrow(() -> new TechnicalException("No client found for name: " + clientName));
            }
        ).flatMap(client ->
          getProfile(webContext, client)
        ).map(profile -> {
          profile.ifPresent(userProfile -> webContext.getProfileManager().save(true, userProfile, false));
          Optional<String> originalUrl = sessionData.get(Pac4jSessionKeys.REQUESTED_URL);
          sessionData.remove(Pac4jSessionKeys.REQUESTED_URL);
          return originalUrl;
        }).onError(t -> {
          if (t instanceof HttpAction) {
            webContext.sendResponse((HttpAction) t);
          } else {
            ctx.error(new TechnicalException("Failed to get user profile", t));
          }
        });
      }).then(originalUrlOption -> {
        ctx.redirect(originalUrlOption.orElse("/"));
      });
    } else {
      createClients(ctx, pathBinding).then(clients -> {
        ctx.getRequest().addLazy(Clients.class, () -> uncheck(() -> clients));
        ctx.next();
      });
    }
  }

  private Promise<Clients> createClients(Context ctx, PathBinding pathBinding) throws Exception {
    String boundTo = pathBinding.getBoundTo();
    PublicAddress publicAddress = ctx.get(PublicAddress.class);
    String absoluteCallbackUrl = publicAddress.get(b -> b.maybeEncodedPath(boundTo).maybeEncodedPath(path))
        .toASCIIString();

    Iterable<? extends Client> result = clientsProvider.get(ctx);

    @SuppressWarnings("rawtypes")
    List<Client> clients;
    if (result instanceof List) {
      clients = Types.cast(result);
    } else {
      clients = ImmutableList.copyOf(result);
    }

    return Promise.value(new Clients(absoluteCallbackUrl, clients));
  }

  private Promise<Optional<UserProfile>> getProfile(RatpackWebContext webContext,
      Client client) throws HttpAction {
    return Blocking.get(
        () -> client.getCredentials(webContext, webContext.getSessionStore())
            .flatMap(credentials -> client.getUserProfile(credentials, webContext, webContext.getSessionStore()))
    );
  }

}
