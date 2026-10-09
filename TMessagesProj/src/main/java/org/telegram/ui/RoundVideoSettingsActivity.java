package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.LumaRoundVideoQuality;
import org.telegram.messenger.LumaRoundVideoCamera;
import org.telegram.messenger.LumaRoundVideoStabilization;
import org.telegram.messenger.LumaRoundVideoStats;
import org.telegram.messenger.LumaBuildPolicy;
import org.telegram.utils.settings.SharedSettings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SlideChooseView;
import org.telegram.utils.camera.roundvideo.RoundVideoSession;

/** All round-video controls: common preferences plus the optional Camera2 recorder. */
public class RoundVideoSettingsActivity extends BaseFragment {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_VALUE = 2;
    private static final int TYPE_INFO = 3;
    private static final int TYPE_SLIDER = 4;

    private static final int ROW_GENERAL_HEADER = 0;
    private static final int ROW_ENABLED = 1;
    private static final int ROW_OUTPUT_RESOLUTION = 2;
    private static final int ROW_CAMERA_RESOLUTION = 3;
    private static final int ROW_BITRATE = 4;
    private static final int ROW_GENERAL_INFO = 5;
    private static final int ROW_REAR_CAMERA = 6;
    private static final int ROW_REAR_CAMERA_INFO = 7;
    private static final int ROW_QUALITY_HEADER = 8;
    private static final int ROW_QUALITY = 9;
    private static final int ROW_QUALITY_INFO = 10;
    private static final int ROW_FRAME_RATE_HEADER = 11;
    private static final int ROW_FRAME_RATE = 12;
    private static final int ROW_FRAME_RATE_INFO = 13;
    private static final int ROW_STABILIZATION_HEADER = 14;
    private static final int ROW_STABILIZATION = 15;
    private static final int ROW_STABILIZATION_INFO = 16;
    private static final int ROW_LAST_RECORDING_HEADER = 17;
    private static final int ROW_LAST_RECORDING_INFO = 18;
    private static final int ROW_COMPOSITION_HEADER = 19;
    private static final int ROW_COMPOSITION = 20;
    private static final int ROW_COMPOSITION_INFO = 21;
    private static final int ROW_COUNT = BuildConfig.DEBUG_PRIVATE_VERSION ? 22 : 19;

    private static final int[] BITRATES = {
            750_000,
            1_000_000,
            1_200_000,
            2_000_000,
            4_000_000,
            6_000_000,
            8_000_000
    };

    private static final RoundVideoSession.OutputResolution[] OUTPUT_RESOLUTIONS = {
            RoundVideoSession.OutputResolution.P360,
            RoundVideoSession.OutputResolution.P480,
            RoundVideoSession.OutputResolution.P640
    };

    private RecyclerListView listView;
    private ListAdapter adapter;

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null) adapter.notifyItemChanged(ROW_LAST_RECORDING_INFO);
    }

    private String lastRecordingInfo() {
        LumaRoundVideoStats.Result result = LumaRoundVideoStats.lastResult();
        if (result == null) return LocaleController.getString(R.string.LumaRoundVideoMeasuredEmpty);
        return LocaleController.formatString(R.string.LumaRoundVideoMeasuredInfo,
                result.width, result.height, result.measuredFps,
                result.videoBitrate / 1_000_000.0, result.requestedFps)
                + "\n" + LocaleController.getString(R.string.LumaRoundVideoMeasuredNote);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.RoundVideoSettings));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> onRowClicked(position));
        frameLayout.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT
        ));
        return fragmentView;
    }

    private void onRowClicked(int position) {
        // Keep the legacy preference intact while Camera2 uses explicit resolution/bitrate.
        if (position == ROW_QUALITY) {
            if (SharedSettings.roundVideoCamera2Enabled.get()) return;
            LumaRoundVideoQuality.setEnabled(!LumaRoundVideoQuality.isEnabled());
            adapter.notifyItemChanged(position);
            return;
        }
        if (position == ROW_REAR_CAMERA) {
            LumaRoundVideoCamera.setStartWithRearCameraEnabled(!LumaRoundVideoCamera.isStartWithRearCameraEnabled());
            adapter.notifyItemChanged(position);
            return;
        }
        if (position == ROW_ENABLED) {
            SharedSettings.roundVideoCamera2Enabled.set(!SharedSettings.roundVideoCamera2Enabled.get());
            adapter.notifyDataSetChanged();
            return;
        }
        if (!SharedSettings.roundVideoCamera2Enabled.get()) {
            return;
        }
        if (position == ROW_OUTPUT_RESOLUTION) {
            showChoice(
                    R.string.RoundVideoOutputResolution,
                    new CharSequence[]{"360 × 360", "480 × 480", "640 × 640"},
                    index -> SharedSettings.roundVideoOutputResolution.set(OUTPUT_RESOLUTIONS[index])
            );
        } else if (position == ROW_CAMERA_RESOLUTION) {
            showChoice(
                    R.string.RoundVideoCameraResolution,
                    new CharSequence[]{
                            LocaleController.getString(R.string.RoundVideoCameraResolutionHigh),
                            LocaleController.getString(R.string.RoundVideoCameraResolutionMedium),
                            LocaleController.getString(R.string.RoundVideoCameraResolutionLow)
                    },
                    index -> SharedSettings.roundVideoCameraResolution.set(
                            RoundVideoSession.CameraResolution.values()[index]
                    )
            );
        } else if (position == ROW_BITRATE) {
            CharSequence[] labels = new CharSequence[BITRATES.length];
            for (int i = 0; i < labels.length; i++) {
                labels[i] = formatBitrate(BITRATES[i]);
            }
            showChoice(
                    R.string.RoundVideoBitrate,
                    labels,
                    index -> SharedSettings.roundVideoVideoBitrate.set(BITRATES[index])
            );
        } else if (position == ROW_COMPOSITION) {
            SharedSettings.roundVideoComposition.set(!SharedSettings.roundVideoComposition.get());
            adapter.notifyItemChanged(position);
        }
    }

    private void showChoice(int titleResId, CharSequence[] choices, ChoiceHandler handler) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(titleResId));
        builder.setItems(choices, (dialog, index) -> {
            handler.onChoice(index);
            adapter.notifyDataSetChanged();
        });
        showDialog(builder.create());
    }

    private static String formatBitrate(int bitrate) {
        if (bitrate % 1_000_000 == 0) {
            return (bitrate / 1_000_000) + " Mbps";
        }
        if (bitrate > 1_000_000) {
            return String.format(java.util.Locale.US, "%.1f Mbps", bitrate / 1_000_000f);
        }
        return (bitrate / 1_000) + " kbps";
    }

    private interface ChoiceHandler {
        void onChoice(int index);
    }

    static String[] stabilizationOptions() {
        String off = LocaleController.getString(R.string.LumaRoundVideoStabilizationOff);
        String standard = LocaleController.getString(R.string.LumaRoundVideoStabilizationStandard);
        return LumaBuildPolicy.allowsEnhancedRoundVideoStabilization()
                ? new String[]{off, standard, LocaleController.getString(R.string.LumaRoundVideoStabilizationEnhanced)}
                : new String[]{off, standard};
    }

    static int stabilizationInfoResource() {
        return LumaBuildPolicy.allowsEnhancedRoundVideoStabilization()
                ? R.string.LumaRoundVideoStabilizationInfo : R.string.LumaRoundVideoStabilizationPublicInfo;
    }

    private static String cameraResolutionLabel(RoundVideoSession.CameraResolution resolution) {
        if (resolution == RoundVideoSession.CameraResolution.HIGH) {
            return LocaleController.getString(R.string.RoundVideoCameraResolutionHigh);
        } else if (resolution == RoundVideoSession.CameraResolution.MEDIUM) {
            return LocaleController.getString(R.string.RoundVideoCameraResolutionMedium);
        }
        return LocaleController.getString(R.string.RoundVideoCameraResolutionLow);
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        private ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return ROW_COUNT;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (position == ROW_QUALITY) {
                return !SharedSettings.roundVideoCamera2Enabled.get();
            }
            if (position == ROW_ENABLED || position == ROW_REAR_CAMERA) {
                return true;
            }
            if (!SharedSettings.roundVideoCamera2Enabled.get()) {
                return false;
            }
            return position == ROW_OUTPUT_RESOLUTION
                    || position == ROW_CAMERA_RESOLUTION
                    || position == ROW_BITRATE
                    || position == ROW_COMPOSITION;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_HEADER) {
                view = new HeaderCell(context);
            } else if (viewType == TYPE_CHECK) {
                view = new TextCheckCell(context);
            } else if (viewType == TYPE_VALUE) {
                TextSettingsCell cell = new TextSettingsCell(context);
                cell.setCanDisable(true);
                view = cell;
            } else if (viewType == TYPE_SLIDER) {
                view = new SlideChooseView(context);
            } else {
                view = new TextInfoPrivacyCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
            ));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            boolean enabled = SharedSettings.roundVideoCamera2Enabled.get();
            if (holder.getItemViewType() == TYPE_HEADER) {
                HeaderCell cell = (HeaderCell) holder.itemView;
                cell.setText(position == ROW_GENERAL_HEADER
                        ? LocaleController.getString(R.string.RoundVideoGeneral)
                        : position == ROW_QUALITY_HEADER ? LocaleController.getString(R.string.LumaRoundVideoLegacyQualityTitle)
                        : position == ROW_FRAME_RATE_HEADER ? LocaleController.getString(R.string.LumaRoundVideoFps)
                        : position == ROW_STABILIZATION_HEADER ? LocaleController.getString(R.string.LumaRoundVideoStabilization)
                        : position == ROW_LAST_RECORDING_HEADER ? LocaleController.getString(R.string.LumaRoundVideoMeasuredTitle)
                        : LocaleController.getString(R.string.RoundVideoComposition));
            } else if (holder.getItemViewType() == TYPE_SLIDER) {
                SlideChooseView cell = (SlideChooseView) holder.itemView;
                cell.setCallback(null);
                if (position == ROW_FRAME_RATE) {
                    int fallback = enabled ? SharedSettings.getRoundVideoFrameRate().getValue() : LumaRoundVideoQuality.HIGH_FRAME_RATE;
                    cell.setOptions(LumaRoundVideoQuality.getPreferredFrameRate(fallback) / 30 - 1, "30", "60");
                    cell.setCallback(LumaRoundVideoQuality::setFrameRateLevel);
                } else {
                    cell.setOptions(LumaRoundVideoStabilization.getMode(), stabilizationOptions());
                    cell.setCallback(LumaRoundVideoStabilization::setMode);
                }
            } else if (holder.getItemViewType() == TYPE_CHECK) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setEnabled(position == ROW_QUALITY ? !enabled : position == ROW_ENABLED || position == ROW_REAR_CAMERA || enabled);
                if (position == ROW_ENABLED) {
                    cell.setTextAndCheck(
                            LocaleController.getString(R.string.RoundVideoUseNewRecorder),
                            enabled,
                            false
                    );
                } else if (position == ROW_QUALITY) {
                    cell.setTextAndCheck(LocaleController.getString(R.string.LumaRoundVideoQualityEnable),
                            LumaRoundVideoQuality.isEnabled(), false);
                } else if (position == ROW_REAR_CAMERA) {
                    cell.setTextAndCheck(LocaleController.getString(R.string.LumaRoundVideoStartRearCamera),
                            LumaRoundVideoCamera.isStartWithRearCameraEnabled(), false);
                } else {
                    cell.setTextAndCheck(
                            LocaleController.getString(R.string.RoundVideoCompositionEnabled),
                            SharedSettings.roundVideoComposition.get(),
                            false
                    );
                }
            } else if (holder.getItemViewType() == TYPE_VALUE) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setEnabled(enabled);
                if (position == ROW_OUTPUT_RESOLUTION) {
                    cell.setTextAndValue(
                            LocaleController.getString(R.string.RoundVideoOutputResolution),
                            SharedSettings.getRoundVideoOutputResolution().getSize() + " × "
                                    + SharedSettings.getRoundVideoOutputResolution().getSize(),
                            true
                    );
                } else if (position == ROW_CAMERA_RESOLUTION) {
                    cell.setTextAndValue(
                            LocaleController.getString(R.string.RoundVideoCameraResolution),
                            cameraResolutionLabel(SharedSettings.roundVideoCameraResolution.get()),
                            true
                    );
                } else {
                    cell.setTextAndValue(
                            LocaleController.getString(R.string.RoundVideoBitrate),
                            formatBitrate(SharedSettings.roundVideoVideoBitrate.get()),
                            false
                    );
                }
            } else {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setText(position == ROW_GENERAL_INFO
                        ? LocaleController.getString(R.string.RoundVideoGeneralInfo)
                        : position == ROW_QUALITY_INFO ? LocaleController.getString(R.string.LumaRoundVideoLegacyQualityInfo)
                                + "\n\n" + LocaleController.getString(R.string.LumaRoundVideoQualityInfo)
                        : position == ROW_REAR_CAMERA_INFO ? LocaleController.getString(R.string.LumaRoundVideoStartRearCameraInfo)
                        : position == ROW_LAST_RECORDING_INFO ? lastRecordingInfo()
                        : position == ROW_FRAME_RATE_INFO ? LocaleController.getString(R.string.LumaRoundVideoFpsInfo)
                        : position == ROW_STABILIZATION_INFO ? LocaleController.getString(stabilizationInfoResource())
                        : LocaleController.getString(R.string.RoundVideoCompositionInfo));
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ROW_GENERAL_HEADER || position == ROW_COMPOSITION_HEADER
                    || position == ROW_QUALITY_HEADER
                    || position == ROW_LAST_RECORDING_HEADER
                    || position == ROW_FRAME_RATE_HEADER || position == ROW_STABILIZATION_HEADER) {
                return TYPE_HEADER;
            }
            if (position == ROW_FRAME_RATE || position == ROW_STABILIZATION) return TYPE_SLIDER;
            if (position == ROW_ENABLED || position == ROW_QUALITY || position == ROW_REAR_CAMERA
                    || position == ROW_COMPOSITION) {
                return TYPE_CHECK;
            }
            if (position == ROW_GENERAL_INFO || position == ROW_COMPOSITION_INFO
                    || position == ROW_QUALITY_INFO || position == ROW_REAR_CAMERA_INFO
                    || position == ROW_LAST_RECORDING_INFO
                    || position == ROW_FRAME_RATE_INFO || position == ROW_STABILIZATION_INFO) {
                return TYPE_INFO;
            }
            return TYPE_VALUE;
        }
    }
}
