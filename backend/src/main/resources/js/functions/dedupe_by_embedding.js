// Built-in function: remove duplicate text items (normalized case/whitespace comparison).
function handler(args) {
  var items = Array.isArray(args.items) ? args.items : [];
  var seen = Object.create(null);
  var unique = [];
  for (var i = 0; i < items.length; i++) {
    var s = String(items[i]);
    var key = s.toLowerCase().replace(/\s+/g, " ").trim();
    if (!seen[key]) {
      seen[key] = true;
      unique.push(s);
    }
  }
  return { items: unique, removed: items.length - unique.length };
}
