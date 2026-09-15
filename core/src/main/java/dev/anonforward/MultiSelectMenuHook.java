package dev.anonforward;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

final class MultiSelectMenuHook {
    private static final String SINGLE_FORWARD = "逐条转发";
    private static final String MULTI_FORWARD = "合并转发";
    private static final String ANONYMOUS_FORWARD = "匿名转发";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<View> decoratedRoots =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean installed;
    private static int foregroundActivities;
    private static WeakReference<Activity> resumedActivity = new WeakReference<>(null);

    private MultiSelectMenuHook() {}

    static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        Application application;
        if (context instanceof Application hostApplication) {
            application = hostApplication;
        } else {
            Context applicationContext = context.getApplicationContext();
            if (!(applicationContext instanceof Application)) {
                throw new IllegalStateException("Host Application is not available");
            }
            application = (Application) applicationContext;
        }
        application.registerActivityLifecycleCallbacks(new LifecycleCallbacks());
        MAIN.post(new MenuScanner(application));
        AFLog.i("Anonymous multi-select menu scanner installed");
    }

    static int hookCount() {
        return installed ? 1 : 0;
    }

    private static final class MenuScanner implements Runnable {
        private final Context context;

        MenuScanner(Context context) {
            this.context = context;
        }

        @Override
        public void run() {
            try {
                if (foregroundActivities > 0) {
                    List<View> roots = getWindowRoots();
                    if (roots.isEmpty()) {
                        Activity activity = resumedActivity.get();
                        if (activity != null) roots.add(activity.getWindow().getDecorView());
                    }
                    for (View root : roots) installMenuItemIfPresent(root, context);
                }
            } catch (Throwable error) {
                AFLog.e("Failed to scan multi-select menu", error);
            } finally {
                MAIN.postDelayed(this, 300);
            }
        }
    }

    private static List<View> getWindowRoots() {
        try {
            Class<?> type = Class.forName("android.view.WindowManagerGlobal");
            Object global = type.getMethod("getInstance").invoke(null);
            try {
                Method method = type.getDeclaredMethod("getRootViews");
                method.setAccessible(true);
                Object value = method.invoke(global);
                if (value instanceof List<?> list) {
                    List<View> roots = new ArrayList<>();
                    for (Object item : list) if (item instanceof View view) roots.add(view);
                    return roots;
                }
            } catch (NoSuchMethodException ignored) {
            }
            Field field = type.getDeclaredField("mViews");
            field.setAccessible(true);
            Object value = field.get(global);
            if (value instanceof List<?> list) {
                List<View> roots = new ArrayList<>();
                for (Object item : list) if (item instanceof View view) roots.add(view);
                return roots;
            }
        } catch (Throwable error) {
            AFLog.e("Unable to enumerate QQ windows", error);
        }
        return new ArrayList<>();
    }

    private static void installMenuItemIfPresent(View root, Context context) {
        if (root == null || decoratedRoots.contains(root)) return;
        List<TextView> labels = new ArrayList<>();
        collectTextViews(root, labels);
        TextView single = findLabel(labels, SINGLE_FORWARD);
        TextView merge = findLabel(labels, MULTI_FORWARD);
        if (single == null || merge == null || findLabel(labels, ANONYMOUS_FORWARD) != null) return;

        ViewGroup common = commonParent(single, merge);
        if (common == null || common instanceof AdapterView<?>
                || common.getClass().getName().contains("RecyclerView")) {
            AFLog.w("Forward choice container is not directly mutable: "
                    + (common == null ? "null" : common.getClass().getName()));
            return;
        }
        View mergeAction = directChild(common, merge);
        if (mergeAction == null) return;
        int mergeIndex = common.indexOfChild(mergeAction);
        if (mergeIndex < 0) return;

        View anonymous = createAnonymousAction(context, merge, mergeAction);
        try {
            common.addView(anonymous, mergeIndex + 1, cloneLayoutParams(mergeAction.getLayoutParams()));
            decoratedRoots.add(root);
            AFLog.i("Inserted anonymous forward after merge forward in " + common.getClass().getName());
        } catch (Throwable error) {
            AFLog.e("Failed to insert anonymous forward action", error);
        }
    }

    private static View createAnonymousAction(Context context, TextView templateText, View mergeAction) {
        TextView label = new TextView(context);
        label.setText(ANONYMOUS_FORWARD);
        label.setTextColor(templateText.getTextColors());
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, templateText.getTextSize());
        label.setGravity(templateText.getGravity());
        label.setTypeface(templateText.getTypeface());
        label.setIncludeFontPadding(templateText.getIncludeFontPadding());
        label.setMaxLines(templateText.getMaxLines());
        label.setEllipsize(templateText.getEllipsize());
        label.setTextAlignment(templateText.getTextAlignment());
        label.setMinWidth(templateText.getMinWidth());
        label.setMinHeight(templateText.getMinHeight());
        label.setPadding(templateText.getPaddingLeft(), templateText.getPaddingTop(),
                templateText.getPaddingRight(), templateText.getPaddingBottom());

        View action;
        if (mergeAction == templateText) {
            action = label;
        } else {
            LinearLayout row = new LinearLayout(context);
            row.setGravity(templateText.getGravity());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(mergeAction.getPaddingLeft(), mergeAction.getPaddingTop(),
                    mergeAction.getPaddingRight(), mergeAction.getPaddingBottom());
            row.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            action = row;
        }
        copyBackground(mergeAction, action);
        action.setEnabled(mergeAction.isEnabled());
        action.setClickable(true);
        action.setFocusable(true);
        action.setContentDescription(ANONYMOUS_FORWARD);
        action.setOnClickListener(view -> {
            if (!mergeAction.isEnabled()) return;
            AnonState.arm();
            NativeSsoHook.rescanAsync();
            Toast.makeText(context, "本次合并转发将匿名化", Toast.LENGTH_SHORT).show();
            MAIN.post(() -> {
                boolean clicked = mergeAction.performClick();
                if (!clicked) clicked = templateText.performClick();
                if (!clicked) {
                    AnonState.disarm();
                    Toast.makeText(context, "无法触发原合并转发按钮", Toast.LENGTH_SHORT).show();
                    AFLog.w("Original merge-forward action did not handle performClick");
                }
            });
        });
        return action;
    }

    private static void copyBackground(View source, View target) {
        Drawable background = source.getBackground();
        if (background == null) return;
        Drawable.ConstantState state = background.getConstantState();
        target.setBackground(state == null ? background : state.newDrawable().mutate());
    }

    private static ViewGroup.LayoutParams cloneLayoutParams(ViewGroup.LayoutParams source) {
        if (source == null) {
            return new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        if (source instanceof LinearLayout.LayoutParams params) return new LinearLayout.LayoutParams(params);
        if (source instanceof FrameLayout.LayoutParams params) return new FrameLayout.LayoutParams(params);
        if (source instanceof RelativeLayout.LayoutParams params) return new RelativeLayout.LayoutParams(params);
        if (source instanceof ViewGroup.MarginLayoutParams params) return new ViewGroup.MarginLayoutParams(params);
        return new ViewGroup.LayoutParams(source);
    }

    private static void collectTextViews(View view, List<TextView> output) {
        if (view instanceof TextView textView) output.add(textView);
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                collectTextViews(group.getChildAt(index), output);
            }
        }
    }

    private static TextView findLabel(List<TextView> labels, String expected) {
        for (TextView label : labels) {
            if (normalize(label.getText()).equals(expected)) return label;
        }
        return null;
    }

    private static String normalize(CharSequence value) {
        return value == null ? "" : value.toString().replaceAll("\\s+", "").trim();
    }

    private static ViewGroup commonParent(View first, View second) {
        Set<ViewParent> firstParents = Collections.newSetFromMap(new WeakHashMap<>());
        for (ViewParent current = first.getParent(); current != null; current = current.getParent()) {
            firstParents.add(current);
        }
        for (ViewParent current = second.getParent(); current != null; current = current.getParent()) {
            if (firstParents.contains(current) && current instanceof ViewGroup group) return group;
        }
        return null;
    }

    private static View directChild(ViewGroup parent, View descendant) {
        View current = descendant;
        while (current.getParent() instanceof View parentView && parentView != parent) current = parentView;
        return current.getParent() == parent ? current : null;
    }

    private static final class LifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity activity, Bundle state) {}
        @Override public void onActivityStarted(Activity activity) { foregroundActivities++; }
        @Override public void onActivityResumed(Activity activity) { resumedActivity = new WeakReference<>(activity); }
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) { foregroundActivities = Math.max(0, foregroundActivities - 1); }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

        @Override
        public void onActivityDestroyed(Activity activity) {
            if (!activity.getClass().getName().contains("ForwardRecentActivity")) return;
            MAIN.postDelayed(() -> {
                if (AnonState.isArmed() && !AnonState.hasForwardStarted()) {
                    AFLog.i("Forward target selection was cancelled; anonymous operation cleared");
                    AnonState.disarm();
                }
            }, 1500);
        }
    }
}
