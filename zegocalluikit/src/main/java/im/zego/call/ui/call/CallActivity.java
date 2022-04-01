package im.zego.call.ui.call;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.KeyguardManager.KeyguardDismissCallback;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.StringRes;
import androidx.lifecycle.ViewModelProvider;

import com.blankj.utilcode.util.ActivityUtils;
import com.blankj.utilcode.util.ResourceUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.gyf.immersionbar.ImmersionBar;
import com.jeremyliao.liveeventbus.LiveEventBus;

import java.util.Locale;
import java.util.Objects;

import im.zego.call.R;
import im.zego.call.ZegoCallManager;
import im.zego.call.auth.AuthInfoManager;
import im.zego.call.constant.Constants;
import im.zego.call.databinding.ActivityCallBinding;
import im.zego.call.ui.BaseActivity;
import im.zego.call.ui.call.CallStateManager.CallStateChangedListener;
import im.zego.call.ui.dialog.VideoSettingsDialog;
import im.zego.call.ui.viewmodel.VideoConfigViewModel;
import im.zego.call.utils.AvatarHelper;
import im.zego.callsdk.model.ZegoCallType;
import im.zego.callsdk.model.ZegoNetWorkQuality;
import im.zego.callsdk.model.ZegoUserInfo;
import im.zego.callsdk.service.ZegoCallService;
import im.zego.callsdk.service.ZegoDeviceService;
import im.zego.callsdk.service.ZegoServiceManager;
import im.zego.callsdk.service.ZegoStreamService;
import im.zego.callsdk.service.ZegoUserService;
import im.zego.zim.enums.ZIMConnectionEvent;
import im.zego.zim.enums.ZIMConnectionState;

public class CallActivity extends BaseActivity<ActivityCallBinding> {

    private static final String TAG = "CallActivity";

    private static final String USER_INFO = "user_info";

    private ZegoUserInfo userInfo;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable missCallRunnable = () -> {
        ZegoCallService callService = ZegoServiceManager.getInstance().callService;
        callService.cancelCall(userInfo.userID, errorCode -> {
            CallStateManager.getInstance().setCallState(null, CallStateManager.TYPE_CALL_MISSED);
        });
    };
    private Runnable finishRunnable = () -> {
        CallStateManager.getInstance().setCallState(null, CallStateManager.TYPE_CALL_MISSED);
    };
    private Runnable timeCountRunnable = new Runnable() {
        @Override
        public void run() {
            time++;
            String timeFormat;
            if (time / 3600 > 0) {
                timeFormat = String
                    .format(Locale.getDefault(), "%02d:%02d:%02d", time / 3600, time / 60 - 60 * (time / 3600),
                        time % 60);
            } else {
                timeFormat = String.format(Locale.getDefault(), "%02d:%02d", time / 60, time % 60);
            }
            binding.callTime.setText(timeFormat);
            LiveEventBus
                    .get(Constants.EVENT_TIMER_CHANGE_KEY, String.class)
                    .post(timeFormat);
            handler.postDelayed(timeCountRunnable, 1000);
        }
    };

    private long time;
    private CallStateChangedListener callStateChangedListener;
    private VideoSettingsDialog videoSettingsDialog;

    public static void startCallActivity(ZegoUserInfo userInfo) {
        Log.d(TAG, "startCallActivity() called with: userInfo = [" + userInfo + "]");
        Activity topActivity = ActivityUtils.getTopActivity();
        Intent intent = new Intent(topActivity, CallActivity.class);
        intent.putExtra(USER_INFO, userInfo);
        topActivity.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        userInfo = (ZegoUserInfo) getIntent().getSerializableExtra(USER_INFO);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

            keyguardManager.requestDismissKeyguard(this, new KeyguardDismissCallback() {
                @Override
                public void onDismissError() {
                    super.onDismissError();
                    Log.d(TAG, "onDismissError() called");
                }

                @Override
                public void onDismissSucceeded() {
                    super.onDismissSucceeded();
                    Log.d(TAG, "onDismissSucceeded() called");
                }

                @Override
                public void onDismissCancelled() {
                    super.onDismissCancelled();
                    Log.d(TAG, "onDismissCancelled() called");
                }
            });
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        super.onCreate(savedInstanceState);
        ImmersionBar.with(this).reset().init();

        VideoConfigViewModel videoConfigViewModel = new ViewModelProvider(this).get(VideoConfigViewModel.class);
        videoConfigViewModel.init();
        videoConfigViewModel.updateVideoConfig();
        videoSettingsDialog = new VideoSettingsDialog(this, videoConfigViewModel);

        initView();
        startObserve();
    }

    private void startObserve() {
        LiveEventBus
                .get(Constants.EVENT_MINIMAL, Boolean.class)
                .observe(this, isMinimal -> {
                    if (isMinimal) {
                        moveTaskToBack(true);
                    }
                });
        LiveEventBus.get(Constants.EVENT_SHOW_SETTINGS, Boolean.class).observe(this, isVideoCall -> {
            videoSettingsDialog.setIsVideoCall(isVideoCall);
            videoSettingsDialog.show();
        });
    }

    private void initView() {
        int typeOfCall = CallStateManager.getInstance().getCallState();
        updateUi(typeOfCall);

        initDeviceState(typeOfCall);

        callStateChangedListener = new CallStateChangedListener() {
            @Override
            public void onCallStateChanged(int before, int after) {
                updateUi(after);
                boolean beforeIsOutgoing = (before == CallStateManager.TYPE_OUTGOING_CALLING_VOICE) ||
                    (before == CallStateManager.TYPE_OUTGOING_CALLING_VIDEO);
                boolean beforeIsInComing = (before == CallStateManager.TYPE_INCOMING_CALLING_VOICE) ||
                    (before == CallStateManager.TYPE_INCOMING_CALLING_VIDEO);
                boolean afterIsAccept = (after == CallStateManager.TYPE_CONNECTED_VOICE) ||
                    (after == CallStateManager.TYPE_CONNECTED_VIDEO);
                if ((beforeIsOutgoing || beforeIsInComing) && afterIsAccept) {
                    time = 0;
                    handler.post(timeCountRunnable);
                    handler.removeCallbacks(missCallRunnable);
                    handler.removeCallbacks(finishRunnable);
                    ZegoDeviceService deviceService = ZegoServiceManager.getInstance().deviceService;
                    deviceService.enableSpeaker(false);
                } else if (after == CallStateManager.TYPE_CALL_CANCELED) {
                    updateStateText(R.string.call_page_status_canceled);
                    finishActivityDelayed();
                } else if (after == CallStateManager.TYPE_CALL_COMPLETED) {
                    updateStateText(R.string.call_page_status_completed);
                    ToastUtils.showShort(R.string.call_page_status_completed);
                    finishActivityDelayed();
                } else if (after == CallStateManager.TYPE_CALL_MISSED) {
                    updateStateText(R.string.call_page_status_missed);
                    finishActivityDelayed();
                } else if (after == CallStateManager.TYPE_CALL_DECLINE) {
                    updateStateText(R.string.call_page_status_declined);
                    finishActivityDelayed();
                }
            }
        };
        CallStateManager.getInstance().addListener(callStateChangedListener);
    }

    private void updateStateText(@StringRes int stringID) {
        binding.layoutOutgoingCall.updateStateText(stringID);
        binding.layoutIncomingCall.updateStateText(stringID);
    }

    private void initDeviceState(int typeOfCall) {
        ZegoUserService userService = ZegoServiceManager.getInstance().userService;
        ZegoCallService callService = ZegoServiceManager.getInstance().callService;
        ZegoDeviceService deviceService = ZegoServiceManager.getInstance().deviceService;
        ZegoStreamService streamService = ZegoServiceManager.getInstance().streamService;

        deviceService.useFrontCamera(true);

        String userID = userService.getLocalUserInfo().userID;
        String token = AuthInfoManager.getInstance().generateToken(userID);
//        String token = TokenManager.getInstance().tokenWrapper.token;
        if (typeOfCall == CallStateManager.TYPE_OUTGOING_CALLING_VOICE) {
            callService.callUser(userInfo, ZegoCallType.Voice, token, errorCode -> {
                if (errorCode == 0) {
                    deviceService.enableMic(true);
                    handler.postDelayed(missCallRunnable, 60 * 1000);
                } else {
                    showWarnTips(getString(R.string.call_page_call_fail, errorCode));
                    finishActivityDelayed();
                }
            });
        } else if (typeOfCall == CallStateManager.TYPE_OUTGOING_CALLING_VIDEO) {
            callService.callUser(userInfo, ZegoCallType.Video, token, errorCode -> {
                if (errorCode == 0) {
                    TextureView textureView = binding.layoutOutgoingCall.getTextureView();
                    deviceService.enableMic(true);
                    deviceService.enableCamera(true);
                    streamService.startPlaying(userService.getLocalUserInfo().userID, textureView);
                    handler.postDelayed(missCallRunnable, 60 * 1000);
                } else {
                    showWarnTips(getString(R.string.call_page_call_fail, errorCode));
                    finishActivityDelayed();
                }
            });
        } else if (typeOfCall == CallStateManager.TYPE_INCOMING_CALLING_VIDEO) {
            handler.postDelayed(finishRunnable, 62 * 1000);
        } else if (typeOfCall == CallStateManager.TYPE_INCOMING_CALLING_VOICE) {
            handler.postDelayed(finishRunnable, 62 * 1000);
        } else if (typeOfCall == CallStateManager.TYPE_CONNECTED_VOICE) {
            handler.post(timeCountRunnable);
            deviceService.enableMic(true);
            deviceService.enableSpeaker(false);
            handler.removeCallbacks(missCallRunnable);
            handler.removeCallbacks(finishRunnable);
        } else if (typeOfCall == CallStateManager.TYPE_CONNECTED_VIDEO) {
            handler.post(timeCountRunnable);
            deviceService.enableMic(true);
            deviceService.enableSpeaker(false);
            deviceService.enableCamera(true);
            handler.removeCallbacks(missCallRunnable);
            handler.removeCallbacks(finishRunnable);
        }
    }

    private void updateUi(int type) {
        binding.layoutOutgoingCall.setUserInfo(userInfo);
        binding.layoutOutgoingCall.setCallType(type);
        binding.layoutIncomingCall.setCallType(type);
        binding.layoutIncomingCall.setUserInfo(userInfo);
        binding.layoutConnectedVoiceCall.setUserInfo(userInfo);
        binding.layoutConnectedVideoCall.setUserInfo(userInfo);
        int resourceID = AvatarHelper.getBlurResourceID(userInfo.userName);
        binding.callUserBg.setImageDrawable(ResourceUtils.getDrawable(resourceID));

        switch (type) {
            case CallStateManager.TYPE_INCOMING_CALLING_VOICE:
            case CallStateManager.TYPE_INCOMING_CALLING_VIDEO:
                binding.layoutIncomingCall.setVisibility(View.VISIBLE);
                binding.layoutOutgoingCall.setVisibility(View.GONE);
                binding.layoutConnectedVideoCall.setVisibility(View.GONE);
                binding.layoutConnectedVoiceCall.setVisibility(View.GONE);
                binding.callTime.setVisibility(View.GONE);
                break;
            case CallStateManager.TYPE_CONNECTED_VOICE:
                binding.layoutIncomingCall.setVisibility(View.GONE);
                binding.layoutOutgoingCall.setVisibility(View.GONE);
                binding.layoutConnectedVideoCall.setVisibility(View.GONE);
                binding.layoutConnectedVoiceCall.setVisibility(View.VISIBLE);
                binding.callTime.setVisibility(View.VISIBLE);
                break;
            case CallStateManager.TYPE_CONNECTED_VIDEO:
                binding.layoutIncomingCall.setVisibility(View.GONE);
                binding.layoutOutgoingCall.setVisibility(View.GONE);
                binding.layoutConnectedVideoCall.setVisibility(View.VISIBLE);
                binding.layoutConnectedVoiceCall.setVisibility(View.GONE);
                binding.callTime.setVisibility(View.VISIBLE);
                break;
            case CallStateManager.TYPE_OUTGOING_CALLING_VOICE:
            case CallStateManager.TYPE_OUTGOING_CALLING_VIDEO:
                binding.layoutIncomingCall.setVisibility(View.GONE);
                binding.layoutOutgoingCall.setVisibility(View.VISIBLE);
                binding.layoutConnectedVideoCall.setVisibility(View.GONE);
                binding.layoutConnectedVoiceCall.setVisibility(View.GONE);
                binding.callTime.setVisibility(View.GONE);
                break;
        }
    }

    private void finishActivityDelayed() {
        handler.postDelayed(() -> {
            finish();
        }, 1000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        CallStateManager.getInstance().setCallState(userInfo, CallStateManager.TYPE_NO_CALL);
        CallStateManager.getInstance().removeListener(callStateChangedListener);
    }

    @Override
    public void onBackPressed() {
    }

    public void onUserInfoUpdated(ZegoUserInfo userInfo) {
        if (Objects.equals(this.userInfo, userInfo)) {
            this.userInfo = userInfo;
        }
        binding.layoutIncomingCall.onUserInfoUpdated(userInfo);
        binding.layoutOutgoingCall.onUserInfoUpdated(userInfo);
        binding.layoutConnectedVideoCall.onUserInfoUpdated(userInfo);
        binding.layoutConnectedVoiceCall.onUserInfoUpdated(userInfo);
    }

    public void onNetworkQuality(String userID, ZegoNetWorkQuality quality) {
        if (quality == ZegoNetWorkQuality.Bad) {
            if (userID.equals(ZegoCallManager.getInstance().getLocalUserInfo().userID)) {
                showLoading(getString(R.string.network_connnect_me_unstable), false);
            } else {
                showLoading(getString(R.string.network_connnect_other_unstable), false);
            }
        } else {
            dismissLoading();
        }
    }

    public void onConnectionStateChanged(ZIMConnectionState state, ZIMConnectionEvent event) {
        if (state == ZIMConnectionState.CONNECTED) {
            dismissLoading();
        } else {
            showLoading(getString(R.string.call_page_call_disconnected), true);
        }
    }
}