const GEMINI_MODEL = "gemini-2.5-flash";
const CF_VISION_MODEL = "@cf/meta/llama-3.2-11b-vision-instruct";
const CF_TEXT_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";
const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
  "Content-Type": "application/json; charset=utf-8"
};
function reply(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: cors });
}
function decodeBase64(value) {
  const binary = atob(value);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}
function parseDirection(text) {
  const cleaned = String(text || "").replace(/```json|```/gi, "").trim();
  try {
    const obj = JSON.parse(cleaned);
    const d = String(obj.direction || "UNCLEAR").toUpperCase();
    return {
      direction: ["UP", "DOWN", "UNCLEAR"].includes(d) ? d : "UNCLEAR",
      reason: String(obj.reason || "Insufficient visual evidence").replace(/[\r\n]+/g, " ").slice(0, 170)
    };
  } catch {
    return { direction: "UNCLEAR", reason: "AI response was not valid JSON" };
  }
}
export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
    if (request.method !== "POST") return reply({ error: "Use POST" }, 405);
    try {
      const body = await request.json();
      const frames = Array.isArray(body.frames) ? body.frames.slice(0, 8) : [];
      const horizon = Number(body.horizonSeconds);
      if (![30, 60].includes(horizon) || frames.length < 3 ||
          frames.some(x => typeof x !== "string" || x.length > 850000)) {
        return reply({ error: "Need 3-8 valid chart frames and a 30/60 second horizon" }, 400);
      }
      const prompt = `You are a cautious visual chart interpreter. These are sequential screenshots of a visible financial chart sampled during the last ${horizon} seconds. Judge whether they show a clear visual directional bias for the NEXT ${horizon} seconds. Read actual chart structure and visible candles if legible. Do not invent prices, OHLC, indicators, volume, or hidden context. Return UNCLEAR if chart/layout is unreadable, frames conflict, chart is flat/noisy, UI obscures it, or evidence is insufficient. UP/DOWN are tentative visual assessments, not reliable forecasts. Output ONLY JSON: {"direction":"UP"|"DOWN"|"UNCLEAR","reason":"brief reason under 18 words"}.`;
      // Primary: Gemini. A quota, HTTP, or temporary error triggers the Workers AI fallback.
      if (env.GEMINI_API_KEY) {
        try {
          const parts = [{ text: prompt }];
          for (let i = 0; i < frames.length; i++) {
            parts.push({ text: `Sequential screenshot ${i + 1} of ${frames.length}:` });
            parts.push({ inline_data: { mime_type: "image/jpeg", data: frames[i] } });
          }
          const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent?key=${env.GEMINI_API_KEY}`, {
            method: "POST", headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ contents: [{ role: "user", parts }],
              generationConfig: { temperature: 0.1, responseMimeType: "application/json", maxOutputTokens: 120 } })
          });
          if (r.ok) {
            const data = await r.json();
            const out = data.candidates?.[0]?.content?.parts?.map(p => p.text || "").join("") || "";
            const parsed = parseDirection(out);
            if (parsed.direction !== "UNCLEAR") return reply({ ...parsed, provider: "Gemini Vision" });
          }
        } catch (_) {}
      }
      // Fallback: vision descriptions via Workers AI, followed by a conservative text synthesis.
      // Keep the account on Workers Free: exceeding its free allocation fails rather than billing overage.
      if (!env.AI || typeof env.AI.run !== "function") {
        return reply({ direction: "UNCLEAR", reason: "Gemini unavailable; Cloudflare AI binding is not configured", provider: "None" });
      }
      try {
        const indices = [...new Set([0, Math.floor((frames.length - 1) / 2), frames.length - 1])];
        const descriptions = [];
        for (const index of indices) {
          const result = await env.AI.run(CF_VISION_MODEL, {
            prompt: `Describe only visible candle/price structure in this screenshot from a sequence of ${frames.length} chart screenshots sampled over ${horizon} seconds. Is the chart readable? Do not forecast from a single image. Be concise.`,
            image: decodeBase64(frames[index]), max_tokens: 110, temperature: 0.1
          });
          const description = typeof result === "string" ? result :
            (result?.description || result?.response || JSON.stringify(result));
          descriptions.push(`Frame ${index + 1}: ${String(description).slice(0, 450)}`);
        }
        const synthesis = await env.AI.run(CF_TEXT_MODEL, {
          messages: [
            { role: "system", content: 'Output only valid JSON {"direction":"UP"|"DOWN"|"UNCLEAR","reason":"under 18 words"}. Be conservative; prefer UNCLEAR.' },
            { role: "user", content: `Compare these sequential vision-model descriptions for a tentative visual bias over the next ${horizon} seconds:\n${descriptions.join("\n")}\nIf descriptions are vague, conflicting, or do not show a consistent direction, answer UNCLEAR. Do not invent chart facts.` }
          ],
          max_tokens: 100, temperature: 0.1
        });
        const text = typeof synthesis === "string" ? synthesis :
          (synthesis?.response || synthesis?.result || JSON.stringify(synthesis));
        return reply({ ...parseDirection(text), provider: "Cloudflare Workers AI fallback" });
      } catch (_) {
        return reply({ direction: "UNCLEAR", reason: "Both AI providers unavailable or free quota exhausted", provider: "None" });
      }
    } catch (_) {
      return reply({ error: "Invalid request or upstream failure" }, 500);
    }
  }
};
