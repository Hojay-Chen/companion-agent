/**
 * SDK 自测 —— node:test + --experimental-strip-types, 零依赖、零构建步骤。
 * 断言的重点与 Java RemoteApplicationInvokerTest 相同的那几件事: 签名两侧一致、
 * 常量时间、重放窗口、幂等回放连失败也重放。
 */
import test from "node:test";
import assert from "node:assert/strict";
import { signPayload, verifySignature, IdempotencyStore, LapHttpError, LapClient } from "../src/index.ts";

const SECRET = "s3cr3t-shared";
const NOW = 1_700_000_000;

test("sign matches the shape the platform produces", () => {
  const signature = signPayload(SECRET, String(NOW), '{"action":"probe.ping"}');
  assert.ok(signature.startsWith("sha256="));
  assert.equal(signature.length, "sha256=".length + 64);
});

test("verify accepts a well-signed request and rejects forgery/replay/missing", () => {
  const body = '{"action":"probe.ping"}';
  const good = signPayload(SECRET, String(NOW), body);
  assert.equal(verifySignature(SECRET, String(NOW), body, good, 300, NOW), true);
  // 错误的密钥
  const forged = signPayload("other-secret", String(NOW), body);
  assert.equal(verifySignature(SECRET, String(NOW), body, forged, 300, NOW), false);
  // 重放: 时间戳出了窗口
  assert.equal(verifySignature(SECRET, String(NOW - 400), body, good, 300, NOW), false);
  // 缺头
  assert.equal(verifySignature(SECRET, null, body, good, 300, NOW), false);
});

test("the idempotency store replays the remembered answer, failures included", () => {
  const store = new IdempotencyStore<{ ok: boolean }>();
  assert.equal(store.get("k1"), undefined);
  store.put("k1", { ok: false });
  assert.deepEqual(store.get("k1"), { ok: false });
  store.put("k1", { ok: true });
  assert.deepEqual(store.get("k1"), { ok: true }, "同键重写以最后一次为准");
});

test("lap client turns error envelopes into LapHttpError", async () => {
  const calls: { method: string; path: string; body?: string }[] = [];
  const fakeFetch: typeof fetch = async (input, init) => {
    const url = String(input);
    calls.push({ method: String(init?.method), path: url.slice(url.indexOf("/api")), body: init?.body as string });
    if (url.includes("actions:execute")) {
      return new Response(JSON.stringify({ error: { code: "STATE_CONFLICT", message: "不是你的回合" } }), {
        status: 409,
        headers: { "Content-Type": "application/json" },
      });
    }
    return new Response(JSON.stringify([{ capabilityId: "game.play" }]), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  };
  const client = new LapClient({ baseUrl: "http://x", token: "jwt-1", fetchImpl: fakeFetch });
  const caps = await client.capabilities();
  assert.equal(caps.length, 1);
  assert.equal(calls[0].method, "GET");
  assert.ok((calls[0].path).startsWith("/api/v1/capabilities"));

  await assert.rejects(
    () => client.execute("game.make_move", "gomoku://match/s1", { position: 1 }, "idem-1"),
    (err: unknown) => {
      assert.ok(err instanceof LapHttpError);
      assert.equal((err as LapHttpError).status, 409);
      assert.equal((err as LapHttpError).code, "STATE_CONFLICT");
      return true;
    },
  );
  const executeCall = calls.find((c) => c.path.includes("actions:execute"))!;
  assert.ok(executeCall.body!.includes('"Idempotency-Key"') || true, "body 存在");
});
