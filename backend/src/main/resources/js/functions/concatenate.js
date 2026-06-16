// Built-in function: join an array of text fragments into a single string.
function handler(args) {
  var items = Array.isArray(args.items) ? args.items : [];
  var sep = args.separator != null ? String(args.separator) : "\n";
  return { result: items.map(String).join(sep), count: items.length };
}
