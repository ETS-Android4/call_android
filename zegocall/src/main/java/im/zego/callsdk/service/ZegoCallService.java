package im.zego.callsdk.service;

import im.zego.callsdk.callback.ZegoCallback;
import im.zego.callsdk.listener.ZegoCallServiceListener;
import im.zego.callsdk.model.ZegoCallInfo;
import im.zego.callsdk.model.ZegoDeclineType;
import im.zego.callsdk.model.ZegoLocalUserStatus;
import im.zego.callsdk.model.ZegoCallType;
import im.zego.callsdk.model.ZegoResponseType;

public abstract class ZegoCallService {

    private ZegoCallServiceListener listener;
    private ZegoLocalUserStatus status;
    public ZegoCallInfo callInfo = new ZegoCallInfo();

    /**
     * Make an outbound call
     * <p>
     * Description: This method can be used to initiate a call to a online user. The called user receives a notification
     * once this method gets called. And if the call is not answered in 60 seconds, you will need to call a method to
     * cancel the call.
     * <p>
     * Call this method at: After the user login
     *
     * @param userID           refers to the ID of the user you want call.
     * @param callType         refers to the call type.  ZegoCallTypeVoice: Voice call.  ZegoCallTypeVideo: Video call.
     * @param createRoomToken: refers to the authentication token. To get this, see the documentation:
     *                         https://docs.zegocloud.com/article/11648
     * @param callback         refers to the callback for make a outbound call.
     */
    public abstract void callUser(String userID, ZegoCallType callType, String createRoomToken,
        ZegoCallback callback);

    /**
     * Cancel a call
     * <p>
     * Description: This method can be used to cancel a call. And the called user receives a notification through
     * callback that the call has been canceled.
     * <p>
     * Call this method at: After the user login
     *
     * @param userID    refers to the ID of the user you are calling.
     * @param callback: refers to the callback for cancel a call.
     */
    public abstract void cancelCall(String userID, ZegoCallback callback);

    public abstract void acceptCall(String joinToken, ZegoCallback callback);

    public abstract void declineCall(String userID, ZegoDeclineType type, ZegoCallback callback);

    /**
     * End a call
     * <p>
     * Description: This method can be used to end a call. After the call is ended, both the caller and called user will
     * be logged out from the room, and the stream publishing and playing stop upon ending.
     * <p>
     * Call this method at: After the user login
     *
     * @param callback refers to the callback for end a call.
     */
    public abstract void endCall(ZegoCallback callback);
}
