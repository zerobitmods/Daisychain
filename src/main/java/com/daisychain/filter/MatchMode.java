package com.daisychain.filter;

import javax.annotation.Nullable;

/** How one filter entry matches items. The id is saved and sent over the network; never reorder. */
public enum MatchMode {
    /** Same item, any data. */
    ITEM(0, "item"),
    /** Same item and exactly the same data (enchantments, damage, name, contents...). */
    DATA(1, "data"),
    /** Any item in a tag. */
    TAG(2, "tag"),
    /** Any item from the same mod (namespace). */
    MOD(3, "mod");

    private static final MatchMode[] BY_ID = {ITEM, DATA, TAG, MOD};

    private final int id;
    private final String key;

    MatchMode(int id, String key) {
        this.id = id;
        this.key = key;
    }

    public int id() {
        return id;
    }

    /** Used in translation keys, e.g. screen.daisychain.entry.match.tag. */
    public String key() {
        return key;
    }

    @Nullable
    public static MatchMode byId(int id) {
        return id >= 0 && id < BY_ID.length ? BY_ID[id] : null;
    }
}
