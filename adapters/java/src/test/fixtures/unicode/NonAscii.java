package fx.unicode;

class NonAscii {
  // ñandú 😀: what comes before a site moves its bytes, not its columns
  String saludo(String nombre) { return "¡hola 😀!" + (nombre == "ñandú" ? "sí" : "no"); }
  boolean olé(int año) { String s = "😀😀"; return año >= 2000 && s.length() > 1; }
}
