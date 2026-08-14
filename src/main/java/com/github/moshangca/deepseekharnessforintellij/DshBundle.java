package com.github.moshangca.deepseekharnessforintellij;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.PropertyKey;

@NonNls
public final class DshBundle extends DynamicBundle {

    public static final String BUNDLE = "messages.DshBundle";

    private static final DshBundle INSTANCE = new DshBundle();

    private DshBundle() {
        super(BUNDLE);
    }

    public static String message(@PropertyKey(resourceBundle = BUNDLE) String key, Object... params) {
        return INSTANCE.getMessage(key, params);
    }

    @Nls
    public static String messagePointer(@PropertyKey(resourceBundle = BUNDLE) String key, Object... params) {
        return INSTANCE.getMessage(key, params);
    }
}
