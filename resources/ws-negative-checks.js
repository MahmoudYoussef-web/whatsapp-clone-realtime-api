/**
 * Negative-path verification for the STOMP session auth fix.
 *
 * Individual checks (each reported separately):
 *  C1 missing token        - CONNECT with NO Authorization header
 *  C2 malformed token      - CONNECT with garbage bearer token
 *  C3 expired token        - CONNECT with a genuinely expired Keycloak JWT
 *                            (client access token lifespan temporarily set to
 *                            30s; waits past exp + Nimbus 60s clock skew)
 *  C4 ghost/deleted user   - valid token for a Keycloak user never synced into
 *                            the local DB, then deleted; observed whether the
 *                            WS accepts it
 *  C5 expiry mid-connection- connection kept open past token expiry, a SEND
 *                            frame issued afterwards; observed whether the
 *                            server re-validates per frame or only at CONNECT
 *
 * Requires: backend on :8080, Keycloak admin on :9090 (admin/admin),
 * alice/bob/password users in realm whatsapp-clone.
 *
 * Usage: NODE_PATH=<ui-node_modules> node ws-negative-checks.js
 */
const stompModule = require('stompjs/lib/stomp.js');
const Stomp = stompModule.Stomp;
const SockJS = require('sockjs-client');
const http = require('http');

const KC = { host: 'localhost', port: 9090, realm: 'whatsapp-clone' };
const APP = { host: 'localhost', port: 8080 };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function request({ host, port, path, method = 'GET', headers = {}, body = null }) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host, port, path, method, headers }, (res) => {
      let data = '';
      res.on('data', (c) => (data += c));
      res.on('end', () => {
        if (res.statusCode >= 300) reject(new Error(`${method} ${path} -> ${res.statusCode} ${data.slice(0, 300)}`));
        else resolve(data ? JSON.parse(data) : null);
      });
    });
    req.on('error', reject);
    if (body !== null) req.write(JSON.stringify(body));
    req.end();
  });
}

async function adminToken() {
  const body = 'client_id=admin-cli&grant_type=password&username=admin&password=admin&scope=openid';
  return rawToken(KC.host, KC.port, '/realms/master/protocol/openid-connect/token', body);
}

function rawToken(host, port, path, formBody) {
  return new Promise((resolve, reject) => {
    const req = http.request({
      host, port, path, method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(formBody) },
    }, (res) => {
      let data = '';
      res.on('data', (c) => (data += c));
      res.on('end', () => (res.statusCode >= 300 ? reject(new Error(`token -> ${res.statusCode} ${data.slice(0, 200)}`)) : resolve(JSON.parse(data).access_token)));
    });
    req.on('error', reject);
    req.write(formBody);
    req.end();
  });
}

const userToken = (u, p) => rawToken(KC.host, KC.port, `/realms/${KC.realm}/protocol/openid-connect/token`,
  `client_id=whatsapp-clone-app&grant_type=password&username=${u}&password=${p}&scope=openid`);

const adminApi = async () => `Bearer ${await adminToken()}`;

async function getClient() {
  const h = await adminApi();
  return (await request({
    host: KC.host, port: KC.port, headers: { Authorization: h },
    path: `/admin/realms/${KC.realm}/clients?clientId=whatsapp-clone-app`,
  }))[0];
}

async function setClientLifespan(secondsOrNull) {
  const h = await adminApi();
  const client = await getClient();
  const patch = JSON.parse(JSON.stringify(client));
  delete patch.access;
  delete patch.origin;
  delete patch.registeredNodes;
  patch.attributes = patch.attributes || {};
  patch.attributes['access.token.lifespan'] = secondsOrNull === null ? null : String(secondsOrNull);
  return request({
    host: KC.host, port: KC.port, method: 'PUT', headers: { Authorization: h, 'Content-Type': 'application/json' },
    path: `/admin/realms/${KC.realm}/clients/${client.id}`, body: patch,
  });
}

async function createUser(username) {
  const h = await adminApi();
  await request({
    host: KC.host, port: KC.port, method: 'POST', headers: { Authorization: h, 'Content-Type': 'application/json' },
    path: `/admin/realms/${KC.realm}/users`,
    body: { username, enabled: true, emailVerified: true, email: `${username}@wa.com`, firstName: 'WS', lastName: 'Ghost', requiredActions: [], credentials: [{ type: 'password', value: 'password', temporary: false }] },
  });
}

async function findUser(username) {
  const h = await adminApi();
  const users = await request({
    host: KC.host, port: KC.port, headers: { Authorization: h },
    path: `/admin/realms/${KC.realm}/users?username=${username}&exact=true`,
  });
  return users[0];
}

async function deleteUser(username) {
  const h = await adminApi();
  const user = await findUser(username);
  if (!user) return;
  await request({
    host: KC.host, port: KC.port, method: 'DELETE', headers: { Authorization: h },
    path: `/admin/realms/${KC.realm}/users/${user.id}`,
  });
}

function rest(path, token, method = 'GET', body = null) {
  return request({
    host: APP.host, port: APP.port, path, method,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body,
  });
}

function decodeJwt(jwt) {
  return JSON.parse(Buffer.from(jwt.split('.')[1], 'base64url').toString());
}

function tryConnect(connectHeaders, timeoutMs = 8000) {
  return new Promise((resolve) => {
    let client;
    let settled = false;
    const timer = setTimeout(() => {
      if (!settled) { settled = true; try { client && client.disconnect(); } catch {} resolve({ accepted: null, error: 'TIMEOUT' }); }
    }, timeoutMs);
    client = Stomp.over(new SockJS(`http://${APP.host}:${APP.port}/ws`));
    client.connect(connectHeaders, () => {
      if (!settled) { settled = true; clearTimeout(timer); resolve({ accepted: true, client }); }
    }, (err) => {
      if (!settled) { settled = true; clearTimeout(timer); resolve({ accepted: false, error: typeof err === 'string' ? err : JSON.stringify(err) }); }
    });
  });
}

function connectOk(token) {
  return tryConnect({ Authorization: `Bearer ${token}` });
}

// stompjs 2.3.3 routes post-connect ERROR frames AND ws close to the error
// callback passed to connect() (client.onerror/onclose are dead fields in this
// version), so a live connection must keep counting errors over time.
function connectLive(token, timeoutMs = 12000) {
  return new Promise((resolve) => {
    let client;
    let errors = 0;
    let latest = null;
    let done = false;
    const finish = (v) => { if (!done) { done = true; clearTimeout(timer); resolve(v); } };
    const timer = setTimeout(() => finish({ accepted: false, error: 'TIMEOUT' }), timeoutMs);
    client = Stomp.over(new SockJS(`http://${APP.host}:${APP.port}/ws`));
    client.connect({ Authorization: `Bearer ${token}` },
      () => finish({ accepted: true, client, errors: () => errors, latest: () => latest }),
      (e) => { errors++; latest = typeof e === 'string' ? e : JSON.stringify(e); });
  });
}

function report(name, pass, detail) {
  console.log(`${pass ? 'PASS' : 'FAIL'} | ${name} | ${detail}`);
  return pass;
}

const results = [];
function check(name, pass, detail) {
  results.push({ name, pass, detail });
  report(name, pass, detail);
}

async function main() {
  let lifespanRestored = true;
  const ghost = `ws-ghost-${Date.now()}`;

  // ------- C1: missing token -------
  const c1 = await tryConnect({});
  check('C1 missing token', c1.accepted === false,
    `expected rejected, got accepted=${c1.accepted}${c1.error ? ` (${c1.error.slice(0, 160)})` : ''}`);

  // ------- C2: malformed token -------
  const c2 = await tryConnect({ Authorization: 'Bearer not.a.real.jwt' });
  check('C2 malformed token', c2.accepted === false,
    `expected rejected, got accepted=${c2.accepted}${c2.error ? ` (${c2.error.slice(0, 160)})` : ''}`);

  // ------- C4: ghost user (before deletion) -------
  await createUser(ghost);
  const ghostToken = await userToken(ghost, 'password');
  const c4a = await connectOk(ghostToken);
  if (c4a.accepted) c4a.client.disconnect();
  check('C4a ghost user (valid token, never synced to local DB)', c4a.accepted === false,
    `expected rejected (ADR-0012: CONNECT checks the local users table), got accepted=${c4a.accepted}${c4a.error ? ` (${c4a.error.slice(0, 160)})` : ''}`);

  // ------- C4: deleted user (token outlives account) -------
  await deleteUser(ghost);
  const c4b = await connectOk(ghostToken);
  if (c4b.accepted) c4b.client.disconnect();
  check('C4b deleted user (token issued before deletion)', c4b.accepted === false,
    `expected rejected (ADR-0012: account row gone), got accepted=${c4b.accepted}${c4b.error ? ` (${c4b.error.slice(0, 160)})` : ''}`);

  // ------- C3 + C5: short-lived tokens -------
  try {
    await setClientLifespan(30);
  } catch (e) {
    check('C5 setup (30s client lifespan)', false, `setClientLifespan(30) failed: ${e.message}`);
    process.exit(1);
  }
  const shortAlice = await userToken('alice', 'password');   // issued ~T0, expires T0+30s
  const shortBob = await userToken('bob', 'password');

  // sanity: happy path works with the short-lived tokens
  const alice = await connectLive(shortAlice);
  const bob = await connectLive(shortBob);
  if (!alice.accepted || !bob.accepted) {
    check('C5 setup (short-lived tokens connect)', false, `alice=${alice.accepted} bob=${bob.accepted}`);
    await setClientLifespan(null).catch(() => {});
    process.exit(1);
  }
  const bobQueue = [];
  bob.client.subscribe(`/user/${decodeJwt(shortBob).sub}/chat`, (m) => bobQueue.push(JSON.parse(m.body)));
  const bobId = (await rest('/api/v1/users', shortAlice)).find((u) => u.email === 'bob@wa.com').id;
  const conv = await rest('/api/v1/conversations', shortAlice, 'POST', { participantId: bobId });
  alice.client.send('/app/typing', {}, JSON.stringify({ conversationId: conv.response, typing: true }));
  await sleep(1200);
  check('C5 setup (typing relay works pre-expiry)', bobQueue.some((n) => n.type === 'TYPING'),
    `bob saw ${bobQueue.filter((n) => n.type === 'TYPING').length} TYPING notification(s)`);

  // wait until the token is past exp AND past NimbusJwtDecoder's default 60s clock skew
  const aliceExp = decodeJwt(shortAlice).exp;
  const waitMs = (aliceExp * 1000 + 70_000) - Date.now();
  console.log(`  waiting ${Math.round(waitMs / 1000)}s for alice token (exp ${new Date(aliceExp * 1000).toISOString()}) to be past expiry + skew...`);
  await sleep(Math.max(waitMs, 1000));

  // ADR-0013: the per-frame exp gate must reject the frame with an ERROR frame
  // and close the session. stompjs 2.3.3 surfaces both through the connect()
  // error callback, which connectLive() keeps counting.
  const errorsBefore = alice.errors();
  console.log('  sending SEND frame after expiry (expect ERROR frame + session close)...');

  alice.client.send('/app/typing', {}, JSON.stringify({ conversationId: conv.response, typing: true }));
  await sleep(1500);
  const typingAfterExpiry = bobQueue.filter((n) => n.type === 'TYPING').length;
  const rejectedAfterExpiry = alice.errors() > errorsBefore;
  check('C5 token expired mid-connection', typingAfterExpiry === 1 && rejectedAfterExpiry,
    `frame after expiry: relayed=${typingAfterExpiry - 1} (expect 0), client observed ERROR/close=${rejectedAfterExpiry} (errors=${alice.errors()}${alice.latest() ? `, ${alice.latest().slice(0, 140)}` : ''})`);

  try { alice.client.disconnect(); } catch {}
  try { bob.client.disconnect(); } catch {}

  // ------- C3: expired token at CONNECT -------
  const waitExpired = (decodeJwt(shortBob).exp * 1000 + 70_000) - Date.now();
  if (waitExpired > 0) await sleep(waitExpired);
  const c3 = await tryConnect({ Authorization: `Bearer ${shortBob}` });
  check('C3 expired token', c3.accepted === false,
    `expected rejected, got accepted=${c3.accepted}${c3.error ? ` (${c3.error.slice(0, 200)})` : ''}`);

  // ------- restore -------
  try {
    await setClientLifespan(null);
    const c = await getClient();
    lifespanRestored = !(c.attributes && c.attributes['access.token.lifespan']);
  } catch (e) {
    lifespanRestored = false;
    console.log(`  restore failed: ${e.message}`);
  }
  try {
    const h = await adminApi();
    const users = await request({
      host: KC.host, port: KC.port, headers: { Authorization: h },
      path: `/admin/realms/${KC.realm}/users?username=ws-ghost&exact=false`,
    });
    for (const u of users) {
      await request({
        host: KC.host, port: KC.port, method: 'DELETE', headers: { Authorization: h },
        path: `/admin/realms/${KC.realm}/users/${u.id}`,
      });
    }
  } catch (e) { console.log(`  ghost cleanup failed: ${e.message}`); }
  check('cleanup (client lifespan restored)', lifespanRestored, `lifespan restored=${lifespanRestored}`);

  const failed = results.filter((r) => !r.pass);
  console.log(`\n${failed.length === 0 ? 'ALL NEGATIVE-PATH CHECKS PASSED' : failed.length + ' CHECK(S) FAILED'}`);
  process.exit(failed.length === 0 ? 0 : 1);
}

main().catch(async (e) => {
  console.error('SCRIPT FAILED:', e.message);
  await setClientLifespan(null).catch(() => {});
  process.exit(1);
});
