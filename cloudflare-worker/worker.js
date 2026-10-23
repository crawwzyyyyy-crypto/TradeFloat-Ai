// TradeFloat Cloudflare Worker proxy. Stores the Gemini key as a Worker secret, not in the APK.
const MODEL = "gemini-2.5-flash";
export default {
  async fetch(request, env) {
    const cors = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
      "Content-Type": "application/json; charset=utf-8"
    };
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
    if (request.method !== "POST") return new Response(JSON.stringify({ error: "Use POST" }), { status: 405, headers: cors });
    if (!env.GEMINI_API_KEY) return new Response(JSON.stringify({ error: "Worker secret GEMINI_API_KEY is not set" }), { status: 500, headers: cors });
    try {
      const body = await request.json();
      const frames = Array.isArray(body.frames) ? body.frames.slice(0, 8) : [];
      const horizon = Number(body.horizonSeconds);
      if (![30, 60].includes(horizon) || frames.length < 3 || frames.some(x => typeof x !== "string" || x.length > 1800000)) {
        return new Response(JSON.stringify({ error: "Need 3-8 valid chart frames and a 30/60 second horizon" }), { status: 400, headers: cors });
      }
      const parts = [{
        text: `You are a cautious chart-image interpreter, not an oracle. The images are sequential screenshots sampled from one visible chart over ${horizon} seconds. Determine only whether the visible chart provides a weak visual bias for the NEXT ${horizon} seconds. First inspect whether these are actually readable financial chart frames. Consider candle direction, swing structure, recent slope and visible timeframe if legible. Do not invent prices, indicators, volume, OHLC or hidden context. Chart pixels alone cannot reliably forecast short-term prices. Return UNCLEAR if frames are ambiguous, UI dominates, chart direction is mixed/flat, the chart is not readable, or evidence is insufficient. Even UP/DOWN are tentative directional assessments, not guarantees. Return ONLY JSON: {"direction":"UP"|"DOWN"|"UNCLEAR","reason":"brief explanation, max 18 words"}. Do not provide confidence percentages or financial advice.`
      }];
      for (const [i, frame] of frames.entries()) {
        parts.push({ text: `Sequential chart screenshot ${i + 1} of ${frames.length}:` });
        parts.push({ inline_data: { mime_type: "image/jpeg", data: frame } });
      }
      const resp = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent?key=${env.GEMINI_API_KEY}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          contents: [{ role: "user", parts }],
          generationConfig: { temperature: 0.1, responseMimeType: "application/json", maxOutputTokens: 120 }
        })
      });
      const raw = await resp.text();
      if (!resp.ok) {
        let msg = `Gemini API returned HTTP ${resp.status}`;
        try { msg = JSON.parse(raw).error?.message || msg; } catch {}
        return new Response(JSON.stringify({ error: msg.slice(0, 180) }), { status: 502, headers: cors });
      }
      const result = JSON.parse(raw);
      const output = result.candidates?.[0]?.content?.parts?.map(p => p.text || "").join("") || "";
      let parsed;
      try { parsed = JSON.parse(output); } catch { parsed = { direction: "UNCLEAR", reason: "AI response was not valid structured output" }; }
      const direction = ["UP", "DOWN", "UNCLEAR"].includes(String(parsed.direction).toUpperCase()) ? String(parsed.direction).toUpperCase() : "UNCLEAR";
      const reason = String(parsed.reason || "Insufficient visual evidence").replace(/[\r\n]+/g, " ").slice(0, 180);
      return new Response(JSON.stringify({ direction, reason }), { status: 200, headers: cors });
    } catch (err) {
      return new Response(JSON.stringify({ error: "Invalid request or upstream failure" }), { status: 500, headers: cors });
    }
  }
};
