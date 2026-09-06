package com.fan.widget;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class AppConfigAdapter extends RecyclerView.Adapter<AppConfigAdapter.ViewHolder> {

    // 模式定义
    private static final String[] MODE_NAMES = {"默认", "静谧模式", "高速模式", "狂暴模式"};
    private static final int[] MODE_VALUES = {0, 1, 2, 4};

    private final Context mContext;
    private final OnModeChangeListener mListener;
    private List<AppInfo> mData = new ArrayList<>();

    public interface OnModeChangeListener {
        void onModeChange(String packageName, int mode);
    }

    public AppConfigAdapter(Context context, OnModeChangeListener listener) {
        mContext = context;
        mListener = listener;
    }

    public void setData(List<AppInfo> data) {
        mData.clear();
        if (data != null) {
            mData.addAll(data);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(mContext).inflate(R.layout.item_app_config, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppInfo info = mData.get(position);
        if (info.icon == null) {
            // 惰性加载：仅对可见项加载图标并缓存到 AppInfo
            try {
                info.icon = mContext.getPackageManager().getApplicationIcon(info.packageName);
            } catch (Exception ignored) {}
        }
        holder.icon.setImageDrawable(info.icon);
        holder.name.setText(info.appName);
        holder.packageName.setText(info.packageName);
        holder.modeText.setText(getModeNameByValue(info.fanMode));
        holder.modeText.setOnClickListener(v -> showModePicker(position));
    }

    @Override
    public int getItemCount() {
        return mData.size();
    }

    // ========== 模式映射 ==========
    private String getModeNameByValue(int modeValue) {
        for (int i = 0; i < MODE_VALUES.length; i++) {
            if (MODE_VALUES[i] == modeValue) {
                return MODE_NAMES[i];
            }
        }
        return MODE_NAMES[0]; // 默认
    }

    // ========== 模式选择对话框 ==========
    private void showModePicker(int position) {
        if (position < 0 || position >= mData.size()) return;
        AppInfo info = mData.get(position);

        AlertDialog dialog = new AlertDialog.Builder(mContext)
                .setTitle("选择风扇模式")
                .setItems(MODE_NAMES, (dialog1, which) -> {
                    int targetMode = MODE_VALUES[which];
                    info.fanMode = targetMode;
                    notifyItemChanged(position);
                    if (mListener != null) {
                        mListener.onModeChange(info.packageName, targetMode);
                    }
                })
                .setNegativeButton("取消", null)
                .create();

        // 应用圆角背景（替换默认背景）
        dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog_round);
        dialog.show();
    }

    // ========== ViewHolder ==========
    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView name;
        TextView packageName;
        TextView modeText;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.iv_app_icon);
            name = itemView.findViewById(R.id.tv_app_name);
            packageName = itemView.findViewById(R.id.tv_app_package);
            modeText = itemView.findViewById(R.id.tv_mode);
        }
    }
}