package javax.lang.model;

/**
 * Runtime shim for {@code javax.lang.model.SourceVersion} on Android ART.
 *
 * <p>Android ART (libcore) does not ship {@code javax.lang.model} (JSR 269 / java.compiler).
 * In Eclipse Compiler for Java (ECJ), {@code org.eclipse.jdt.internal.compiler.batch.FileSystem.<clinit>}
 * executes:
 * <pre>{@code
 * try {
 *     isJRE12Plus = SourceVersion.valueOf("RELEASE_12") != null;
 * } catch (IllegalArgumentException e) {
 * }
 * }</pre>
 *
 * <p>On standard Android ART, without this shim class in the DEX, ART fails resolution of
 * {@code Ljavax/lang/model/SourceVersion;} with {@link NoClassDefFoundError}, which is an {@link Error}
 * rather than an {@link IllegalArgumentException}. This causes {@code FileSystem} static initialization
 * to abort with {@link ExceptionInInitializerError}.
 *
 * <p>By defining this enum with constants stopping at {@code RELEASE_11}, calling
 * {@code SourceVersion.valueOf("RELEASE_12")} throws {@link IllegalArgumentException}, which ECJ's
 * catch block expects and handles. This leaves {@code isJRE12Plus = false}, which is the correct
 * behavior for Android ART (as Android lacks desktop JDK 12+ ct.sym module paths).
 */
public enum SourceVersion {
    RELEASE_0,
    RELEASE_1,
    RELEASE_2,
    RELEASE_3,
    RELEASE_4,
    RELEASE_5,
    RELEASE_6,
    RELEASE_7,
    RELEASE_8,
    RELEASE_9,
    RELEASE_10,
    RELEASE_11;

    public static SourceVersion latest() {
        return RELEASE_8;
    }

    public static SourceVersion latestSupported() {
        return RELEASE_8;
    }

    public static boolean isIdentifier(CharSequence name) {
        if (name == null || name.length() == 0) {
            return false;
        }
        int cp = Character.codePointAt(name, 0);
        if (!Character.isJavaIdentifierStart(cp)) {
            return false;
        }
        for (int i = Character.charCount(cp); i < name.length(); i += Character.charCount(cp)) {
            cp = Character.codePointAt(name, i);
            if (!Character.isJavaIdentifierPart(cp)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isName(CharSequence name) {
        if (name == null || name.length() == 0) {
            return false;
        }
        String s = name.toString();
        for (String part : s.split("\\.", -1)) {
            if (!isIdentifier(part) || isKeyword(part)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isName(CharSequence name, SourceVersion version) {
        return isName(name);
    }

    public static boolean isIdentifier(CharSequence name, SourceVersion version) {
        return isIdentifier(name);
    }

    public static boolean isKeyword(CharSequence s) {
        if (s == null || s.length() == 0) {
            return false;
        }
        switch (s.toString()) {
            case "abstract": case "assert": case "boolean": case "break": case "byte":
            case "case": case "catch": case "char": case "class": case "const":
            case "continue": case "default": case "do": case "double": case "else":
            case "enum": case "extends": case "final": case "finally": case "float":
            case "for": case "goto": case "if": case "implements": case "import":
            case "instanceof": case "int": case "interface": case "long": case "native":
            case "new": case "package": case "private": case "protected": case "public":
            case "return": case "short": case "static": case "strictfp": case "super":
            case "switch": case "synchronized": case "this": case "throw": case "throws":
            case "transient": case "try": case "void": case "volatile": case "while":
            case "true": case "false": case "null": case "_":
                return true;
            default:
                return false;
        }
    }

    public static boolean isKeyword(CharSequence s, SourceVersion version) {
        return isKeyword(s);
    }
}
