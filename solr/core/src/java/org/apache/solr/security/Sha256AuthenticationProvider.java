/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.security;

import static org.apache.solr.handler.admin.SecurityConfHandler.getMapValue;

import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.apache.solr.api.AnnotatedApi;
import org.apache.solr.api.Api;
import org.apache.solr.common.util.CommandOperation;
import org.apache.solr.common.util.ValidatingJsonMap;
import org.apache.solr.handler.admin.api.ModifyBasicAuthConfigAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Sha256AuthenticationProvider
    implements ConfigEditablePlugin, BasicAuthPlugin.AuthenticationProvider {

  static String CANNOT_DELETE_LAST_USER_ERROR =
      "You cannot delete the last user. At least one user must be configured at all times.";
  private Map<String, String> credentials;
  private String realm;
  private Map<String, String> promptHeader;

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  static void putUser(String user, String pwd, Map<? super String, ? super String> credentials) {
    if (user == null || pwd == null) return;
    String val = getSaltedHashedValue(pwd);
    credentials.put(user, val);
  }

  public static String getSaltedHashedValue(String pwd) {
    final Random r = new SecureRandom();
    byte[] salt = new byte[32];
    r.nextBytes(salt);
    String saltBase64 = Base64.getEncoder().encodeToString(salt);
    String val = sha256(pwd, saltBase64) + " " + saltBase64;
    return val;
  }

  @Override
  public void init(Map<String, Object> pluginConfig) {
    if (pluginConfig.containsKey(BasicAuthPlugin.PROPERTY_REALM)) {
      this.realm = (String) pluginConfig.get(BasicAuthPlugin.PROPERTY_REALM);
    } else {
      this.realm = "solr";
    }

    promptHeader =
        Collections.unmodifiableMap(
            Collections.singletonMap("WWW-Authenticate", "Basic realm=\"" + realm + "\""));
    credentials = new LinkedHashMap<>();
    @SuppressWarnings({"unchecked"})
    Map<String, String> users = (Map<String, String>) pluginConfig.get("credentials");
    if (users == null || users.isEmpty()) {
      throw new IllegalStateException(
          "No users configured yet. At least one user must be configured in security.json");
    }
    for (Map.Entry<String, String> e : users.entrySet()) {
      String v = e.getValue();
      if (v == null) {
        log.warn("user has no password {}", e.getKey());
        continue;
      }
      credentials.put(e.getKey(), v);
    }
  }

  @Override
  public boolean authenticate(String username, String password) {
    String cred = credentials.get(username);
    if (cred == null || cred.isEmpty()) return false;
    cred = cred.trim();
    String salt = null;
    if (cred.contains(" ")) {
      String[] ss = cred.split(" ");
      if (ss.length > 1 && !ss[1].isEmpty()) {
        salt = ss[1];
        cred = ss[0];
      }
    }
    return cred.equals(sha256(password, salt));
  }

  @Override
  public Map<String, String> getPromptHeaders() {
    return promptHeader;
  }

  public static String sha256(String password, String saltKey) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      log.error("Cannot find algorithm ", e);
      return null; // should not happen
    }
    if (saltKey != null) {
      digest.reset();
      digest.update(Base64.getDecoder().decode(saltKey));
    }

    byte[] btPass = digest.digest(password.getBytes(StandardCharsets.UTF_8));
    digest.reset();
    btPass = digest.digest(btPass);
    return Base64.getEncoder().encodeToString(btPass);
  }

  @Override
  public Map<String, Object> edit(Map<String, Object> latestConf, List<CommandOperation> commands) {
    for (CommandOperation cmd : commands) {
      if (!supported_ops.contains(cmd.name)) {
        cmd.unknownOperation();
        return null;
      }
      if (cmd.hasError()) return null;
      if ("delete-user".equals(cmd.name)) {
        List<String> names = cmd.getStrs("");
        Map<?, ?> map = (Map<?, ?>) latestConf.get("credentials");
        if (map == null || !map.keySet().containsAll(names)) {
          cmd.addError("No such user(s) " + names);
          return null;
        }
        for (String name : names) {
          if (map.containsKey(name)) {
            if (map.size() == 1) {
              cmd.addError(CANNOT_DELETE_LAST_USER_ERROR);
              return null;
            }
          }
          map.remove(name);
        }
        return latestConf;
      }
      if ("set-user".equals(cmd.name)) {
        Map<String, Object> map = getMapValue(latestConf, "credentials");
        Map<String, Object> kv = cmd.getDataMap();
        for (Map.Entry<String, Object> e : kv.entrySet()) {
          if (e.getKey() == null || e.getValue() == null) {
            cmd.addError("name and password must be non-null");
            return null;
          }
          putUser(e.getKey(), String.valueOf(e.getValue()), map);
        }
      }
    }
    return latestConf;
  }

  @Override
  public ValidatingJsonMap getSpec() {
    final List<Api> apis = AnnotatedApi.getApis(new ModifyBasicAuthConfigAPI());
    return apis.get(0).getSpec();
  }

  static final Set<String> supported_ops = Set.of("set-user", "delete-user");
}
