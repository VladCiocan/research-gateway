// Built-in function: fetch a URL over HTTP(S) and return its readable text content.
// Uses the sandboxed httpGet bridge provided by the runtime.
function handler(args) {
  var url = args.url != null ? String(args.url) : "";
  if (!url || !(url.indexOf("http://") === 0 || url.indexOf("https://") === 0)) {
    return { error: "A valid http(s) url is required" };
  }
  var r = httpGet(url);
  if (!r.ok) {
    return { url: url, error: r.error || ("HTTP " + r.status) };
  }
  var text = String(r.body)
    .replace(/<script[\s\S]*?<\/script>/gi, " ")
    .replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (text.length > 4000) text = text.substring(0, 4000) + "…";
  return { url: url, text: text };
}
