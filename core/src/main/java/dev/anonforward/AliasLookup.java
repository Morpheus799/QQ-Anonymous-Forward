package dev.anonforward;

interface AliasLookup {
    String aliasFor(String uid, long uin, String name);
    String aliasForName(String name);
    String replaceKnownPreviewPrefix(String text);
}
