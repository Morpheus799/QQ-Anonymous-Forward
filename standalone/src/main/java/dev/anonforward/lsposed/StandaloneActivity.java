package dev.anonforward.lsposed;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class StandaloneActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("匿名转发 · LSPosed");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView description = new TextView(this);
        description.setText("1. 在 LSPosed 中启用本模块。\n"
                + "2. 作用域选择 QQ 或 TIM。\n"
                + "3. 强行停止并重新启动 QQ/TIM。\n"
                + "4. 多选消息后点击转发，在菜单中选择‘匿名转发’。\n\n"
                + "独立模块无需安装 QAuxiliary。请勿同时启用 QAux 外部插件版本。");
        description.setTextSize(17);
        description.setPadding(0, padding, 0, 0);
        root.addView(description, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }
}
