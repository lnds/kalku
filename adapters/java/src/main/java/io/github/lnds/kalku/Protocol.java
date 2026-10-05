package io.github.lnds.kalku;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The kalku protocol: what is asked, and how it is answered.
 *
 * <p>A request is decoded strictly: a line that is not exactly what the protocol says is
 * refused by kind, with the path to the field that is wrong, and nothing is guessed. What this
 * kalku says is canonical: {@code type}, then {@code id}, then the fields in the order the
 * tables list them, with no whitespace.
 */
final class Protocol {
  private Protocol() {}

  static final int VERSION = 1;

  /** The ceiling on one line, in bytes. The protocol's own. */
  static final int MAX_LINE = 4 * 1024 * 1024;

  // ---- what a kalku is asked -------------------------------------------------

  /** One decoded request. */
  static final class Request {
    final long id;
    final String type;
    // `hello`
    long protocol;
    String root;
    String reni;
    // `sites`
    List<String> files;
    List<String> spells;
    List<String> excludeCalls;
    // `abort`
    long cast;

    Request(long id, String type) {
      this.id = id;
      this.type = type;
    }
  }

  // ---- when it cannot be read ------------------------------------------------

  static final String LINE_TOO_LONG = "line_too_long";
  static final String NOT_JSON = "not_json";
  static final String NOT_OBJECT = "not_object";
  static final String MISSING_TYPE = "missing_type";
  static final String UNKNOWN_TYPE = "unknown_type";
  static final String MISSING_ID = "missing_id";
  static final String BAD_FIELD = "bad_field";

  /** A line that is not a request, by the protocol's own kinds. */
  static final class DecodeError extends Exception {
    private static final long serialVersionUID = 1L;

    final String kind;
    final String detail;
    // The request id, when the line got far enough to have one.
    Long id;

    DecodeError(String kind, String detail) {
      super(kind + ": " + detail);
      this.kind = kind;
      this.detail = detail;
    }
  }

  private static final List<String> REQUESTS =
      Arrays.asList(
          "hello", "prepare", "baseline", "sites", "cast", "abort", "reset", "reload", "delegate",
          "shutdown");

  private static DecodeError bad(String path, String why) {
    return new DecodeError(BAD_FIELD, path + ": " + why);
  }

  /** One request line. */
  static Request decode(String line) throws DecodeError {
    Object value;
    try {
      value = Json.decode(line);
    } catch (Json.Invalid e) {
      throw new DecodeError(NOT_JSON, e.getMessage());
    }
    if (!(value instanceof Map)) {
      throw new DecodeError(NOT_OBJECT, "the line is not a JSON object");
    }
    Map<?, ?> o = (Map<?, ?>) value;
    Object type = o.get("type");
    if (!(type instanceof String)) {
      throw new DecodeError(MISSING_TYPE, "no string `type`");
    }
    Object id = o.get("id");
    if (!REQUESTS.contains(type)) {
      DecodeError unknown = new DecodeError(UNKNOWN_TYPE, (String) type);
      unknown.id = id instanceof Long ? (Long) id : null;
      throw unknown;
    }
    if (!(id instanceof Long)) {
      throw new DecodeError(MISSING_ID, "no integer `id`");
    }
    Request request = new Request((Long) id, (String) type);
    try {
      body(request, o);
    } catch (DecodeError e) {
      e.id = request.id;
      throw e;
    }
    return request;
  }

  private static void body(Request r, Map<?, ?> o) throws DecodeError {
    switch (r.type) {
      case "hello":
        r.protocol = integer(o, "protocol", "");
        r.root = string(o, "root", "");
        r.reni = string(o, "reni", "");
        integer(o, "worker", "");
        integer(o, "inline_limit_bytes", "");
        env(o);
        break;
      case "sites":
        r.files = strings(o, "files", "");
        r.spells = spells(o);
        r.excludeCalls = strings(o, "exclude_calls", "");
        break;
      case "cast":
        string(o, "wekufe", "");
        site(required(o, "site", ""), "site");
        strings(o, "tests", "");
        break;
      case "abort":
        r.cast = integer(o, "cast", "");
        break;
      case "reload":
        strings(o, "files", "");
        break;
      case "delegate":
        scope(required(o, "scope", ""));
        break;
      default:
        // `prepare`, `baseline`, `reset` and `shutdown` carry nothing.
        break;
    }
  }

  // ---- field readers, each naming its path when it fails ---------------------

  private static String path(String parent, String key) {
    return parent.isEmpty() ? key : parent + "." + key;
  }

  private static Object required(Map<?, ?> o, String key, String parent) throws DecodeError {
    Object v = o.get(key);
    if (v == null) {
      throw bad(path(parent, key), "is required");
    }
    return v;
  }

  private static String string(Map<?, ?> o, String key, String parent) throws DecodeError {
    Object v = required(o, key, parent);
    if (!(v instanceof String)) {
      throw bad(path(parent, key), "must be a string");
    }
    return (String) v;
  }

  private static long integer(Map<?, ?> o, String key, String parent) throws DecodeError {
    Object v = required(o, key, parent);
    if (!(v instanceof Long)) {
      throw bad(path(parent, key), "must be an integer");
    }
    return (Long) v;
  }

  private static List<String> strings(Map<?, ?> o, String key, String parent)
      throws DecodeError {
    String here = path(parent, key);
    Object v = required(o, key, parent);
    if (!(v instanceof List)) {
      throw bad(here, "must be an array");
    }
    List<String> out = new ArrayList<>();
    int i = 0;
    for (Object item : (List<?>) v) {
      if (!(item instanceof String)) {
        throw bad(here + "[" + i + "]", "must be a string");
      }
      out.add((String) item);
      i++;
    }
    return out;
  }

  private static void env(Map<?, ?> o) throws DecodeError {
    Object v = required(o, "env", "");
    if (!(v instanceof Map)) {
      throw bad("env", "must be an object");
    }
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) v).entrySet()) {
      if (!(entry.getValue() instanceof String)) {
        throw bad("env." + entry.getKey(), "must be a string");
      }
    }
  }

  private static List<String> spells(Map<?, ?> o) throws DecodeError {
    List<String> names = strings(o, "spells", "");
    for (int i = 0; i < names.size(); i++) {
      if (!Spell.known(names.get(i))) {
        throw bad("spells[" + i + "]", "`" + names.get(i) + "` is not a spell");
      }
    }
    return names;
  }

  private static void optionalString(Map<?, ?> o, String key, String parent)
      throws DecodeError {
    Object v = o.get(key);
    if (v != null && !(v instanceof String)) {
      throw bad(path(parent, key), "must be a string");
    }
  }

  // A site as `cast` carries it: every field the table lists, in its shape.
  private static void site(Object value, String at) throws DecodeError {
    if (!(value instanceof Map)) {
      throw bad(at, "must be an object");
    }
    Map<?, ?> site = (Map<?, ?>) value;
    string(site, "site_id", at);
    string(site, "file", at);
    optionalString(site, "enclosing", at);
    Object ordinal = site.get("ordinal");
    if (ordinal != null && !(ordinal instanceof Long)) {
      throw bad(path(at, "ordinal"), "must be an integer");
    }
    span(required(site, "span", at), path(at, "span"));
    String spell = string(site, "spell", at);
    if (!Spell.known(spell)) {
      throw bad(path(at, "spell"), "`" + spell + "` is not a spell");
    }
    optionalString(site, "original", at);
    string(site, "replacement", at);
    String reload = string(site, "reload", at);
    if (!reload.equals("module") && !reload.equals("dependents")) {
      throw bad(path(at, "reload"), "must be `module` or `dependents`");
    }
  }

  private static void span(Object value, String at) throws DecodeError {
    if (!(value instanceof Map)) {
      throw bad(at, "must be an object");
    }
    Map<?, ?> span = (Map<?, ?>) value;
    position(required(span, "start", at), path(at, "start"));
    Object end = span.get("end");
    if (end != null) {
      position(end, path(at, "end"));
    }
  }

  private static void position(Object value, String at) throws DecodeError {
    if (!(value instanceof Map)) {
      throw bad(at, "must be an object");
    }
    for (String key : Arrays.asList("line", "col", "byte")) {
      integer((Map<?, ?>) value, key, at);
    }
  }

  // Exactly one of `all`, `since`, `files`.
  private static void scope(Object value) throws DecodeError {
    if (!(value instanceof Map)) {
      throw bad("scope", "must be an object");
    }
    Map<?, ?> scope = (Map<?, ?>) value;
    List<String> keys = new ArrayList<>();
    for (String key : Arrays.asList("all", "since", "files")) {
      if (scope.get(key) != null) {
        keys.add(key);
      }
    }
    if (keys.isEmpty()) {
      throw bad("scope", "needs one of `all`, `since` or `files`");
    }
    if (keys.size() > 1) {
      throw bad("scope", "takes exactly one of `all`, `since` or `files`");
    }
    switch (keys.get(0)) {
      case "all":
        if (!Boolean.TRUE.equals(scope.get("all"))) {
          throw bad("scope.all", "can only be `true`");
        }
        break;
      case "since":
        string(scope, "since", "scope");
        break;
      default:
        strings(scope, "files", "scope");
        break;
    }
  }

  // ---- what a kalku says -----------------------------------------------------

  private static Map<String, Object> message(String type, long id) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("type", type);
    out.put("id", id);
    return out;
  }

  static String error(long id, String code, String message, boolean fatal) {
    Map<String, Object> out = message("error", id);
    out.put("code", code);
    out.put("message", message);
    out.put("fatal", fatal);
    return Json.encode(out);
  }

  static String ready(long id, String adapter, String runtime, List<String> capabilities) {
    Map<String, Object> out = message("ready", id);
    out.put("protocol", (long) VERSION);
    out.put("language", "java");
    out.put("adapter", adapter);
    out.put("runtime", runtime);
    out.put("spells", Spell.CAST);
    out.put("capabilities", capabilities);
    return Json.encode(out);
  }

  static String sitesFound(long id, List<?> sites, List<?> skipped) {
    Map<String, Object> out = message("sites_found", id);
    out.put("sites", sites);
    out.put("skipped", skipped);
    return Json.encode(out);
  }

  static String aborted(long id, long cast, boolean restored) {
    Map<String, Object> out = message("aborted", id);
    out.put("cast", cast);
    out.put("restored", restored);
    return Json.encode(out);
  }

  static String bye(long id) {
    return Json.encode(message("bye", id));
  }

  static Map<String, Object> skipped(String file, String reason, String message) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("file", file);
    out.put("reason", reason);
    out.put("message", message);
    return out;
  }

  /** A site as the protocol states it, in the order its table lists it. */
  static Map<String, Object> site(Sites.Site s) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("site_id", s.siteId);
    out.put("file", s.file);
    if (s.enclosing != null) {
      out.put("enclosing", s.enclosing);
    }
    out.put("ordinal", (long) s.ordinal);
    Map<String, Object> span = new LinkedHashMap<>();
    span.put("start", position(s.start));
    span.put("end", position(s.end));
    out.put("span", span);
    out.put("spell", s.spell);
    out.put("original", s.original);
    out.put("replacement", s.replacement);
    out.put("reload", "module");
    return out;
  }

  private static Map<String, Object> position(Source.Position p) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("line", (long) p.line);
    out.put("col", (long) p.col);
    out.put("byte", (long) p.at);
    return out;
  }
}
