// Built-in function: score and rank text items by keyword overlap with a query (0..1).
function handler(args) {
  var query = args.query != null ? String(args.query) : "";
  var items = Array.isArray(args.items) ? args.items : [];

  function tokens(s) {
    var out = Object.create(null);
    String(s).toLowerCase().split(/[^a-z0-9]+/).forEach(function (w) {
      if (w.length > 2) out[w] = true;
    });
    return out;
  }

  var qKeys = Object.keys(tokens(query));
  var ranked = items.map(function (o) {
    var s = String(o);
    var t = tokens(s);
    var overlap = qKeys.filter(function (k) { return t[k]; }).length;
    var score = qKeys.length === 0 ? 0 : Math.min(1, overlap / qKeys.length);
    return { text: s, score: Math.round(score * 100) / 100 };
  }).sort(function (a, b) { return b.score - a.score; });

  return { ranked: ranked };
}
