/*
 * Copyright (c) 2015 Layer. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.layer.atlas;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.layer.atlas.Atlas.ImageLoader;
import com.layer.atlas.Atlas.ImageLoader.BitmapLoadListener;
import com.layer.atlas.Atlas.ImageLoader.ImageSpec;
import com.layer.atlas.Atlas.ImageLoader.MessagePartStreamProvider;
import com.layer.atlas.Atlas.Tools;
import com.layer.sdk.LayerClient;
import com.layer.sdk.changes.LayerChange;
import com.layer.sdk.changes.LayerChange.Type;
import com.layer.sdk.changes.LayerChangeEvent;
import com.layer.sdk.listeners.LayerChangeEventListener;
import com.layer.sdk.listeners.LayerProgressListener;
import com.layer.sdk.messaging.Conversation;
import com.layer.sdk.messaging.LayerObject;
import com.layer.sdk.messaging.Message;
import com.layer.sdk.messaging.Message.RecipientStatus;
import com.layer.sdk.messaging.MessagePart;

/**
 * @author Oleg Orlov
 * @since 13 May 2015
 */
public class AtlasMessagesList extends FrameLayout implements LayerChangeEventListener.MainThread {
    private static final String TAG = AtlasMessagesList.class.getSimpleName();
    private static final boolean debug = false;

    private static final boolean CLUSTERED_BUBBLES = true;

    private static final int MESSAGE_TYPE_UPDATE_VALUES = 0;
    private static final int MESSAGE_REFRESH_UPDATE_ALL = 0;
    private static final int MESSAGE_REFRESH_UPDATE_DELIVERY = 1;

    private final DateFormat timeFormat;

    private ListView messagesList;
    private BaseAdapter messagesAdapter;

    private ArrayList<Cell> cells = new ArrayList<Cell>();

    private LayerClient client;
    private Conversation conv;

    private Message latestReadMessage = null;
    private Message latestDeliveredMessage = null;

    private ItemClickListener clickListener;

    //styles
    private static final float CELL_CONTAINER_ALPHA_UNSENT = 0.5f;
    private static final float CELL_CONTAINER_ALPHA_SENT = 1.0f;
    private int myBubbleColor;
    private int myTextColor;
    private int myTextStyle;
    private float myTextSize;
    private Typeface myTextTypeface;

    private int otherBubbleColor;
    private int otherTextColor;
    private int otherTextStyle;
    private float otherTextSize;
    private Typeface otherTextTypeface;

    private int dateTextColor;
    private int avatarTextColor;
    private int avatarBackgroundColor;
    private boolean isReverseOrder = false;
    private boolean showSystemMessages = false;

    public AtlasMessagesList(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        parseStyle(context, attrs, defStyle);
        this.timeFormat = android.text.format.DateFormat.getTimeFormat(context);
    }

    public AtlasMessagesList(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AtlasMessagesList(Context context) {
        super(context);
        this.timeFormat = android.text.format.DateFormat.getTimeFormat(context);
    }

    public void init(final LayerClient layerClient, final Atlas.ParticipantProvider participantProvider) {
        if (layerClient == null) throw new IllegalArgumentException("LayerClient cannot be null");
        if (participantProvider == null)
            throw new IllegalArgumentException("ParticipantProvider cannot be null");
        if (messagesList != null)
            throw new IllegalStateException("AtlasMessagesList is already initialized!");

        this.client = layerClient;
        LayoutInflater.from(getContext()).inflate(R.layout.atlas_messages_list, this);

        // --- message view
        messagesList = (ListView) findViewById(R.id.atlas_messages_list);
        messagesList.setAdapter(messagesAdapter = new BaseAdapter() {

            public View getView(int position, View convertView, ViewGroup parent) {
                final Cell cell = getItem(position);
                MessagePart part = cell.messagePart;
                String userId = part.getMessage().getSender().getUserId();

                boolean myMessage = client.getAuthenticatedUserId().equals(userId);

                if (convertView == null) {
                    convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.atlas_view_messages_convert, parent, false);
                }

                View spacerTop = convertView.findViewById(R.id.atlas_view_messages_convert_spacer_top);
                spacerTop.setVisibility(cell.clusterItemId == cell.clusterHeadItemId && !cell.timeHeader ? View.VISIBLE : View.GONE);

                View spacerBottom = convertView.findViewById(R.id.atlas_view_messages_convert_spacer_bottom);
                spacerBottom.setVisibility(cell.clusterTail ? View.VISIBLE : View.GONE);

                // format date
                View timeBar = convertView.findViewById(R.id.atlas_view_messages_convert_timebar);
                TextView timeBarDay = (TextView) convertView.findViewById(R.id.atlas_view_messages_convert_timebar_day);
                TextView timeBarTime = (TextView) convertView.findViewById(R.id.atlas_view_messages_convert_timebar_time);
                if (cell.timeHeader) {

                    Calendar cal = Calendar.getInstance();
                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.set(Calendar.SECOND, 0);
                    long todayMidnight = cal.getTimeInMillis();
                    long yesterMidnight = todayMidnight - Tools.TIME_HOURS_24;
                    long weekAgoMidnight = todayMidnight - Tools.TIME_HOURS_24 * 7;
                    Date sentAt = cell.messagePart.getMessage().getSentAt();
                    if (sentAt == null) sentAt = new Date();

                    String timeBarTimeText = timeFormat.format(sentAt.getTime());
                    String timeBarDayText = null;
                    if (sentAt.getTime() > todayMidnight) {
                        timeBarDayText = "Today";
                    } else if (sentAt.getTime() > yesterMidnight) {
                        timeBarDayText = "Yesterday";
                    } else if (sentAt.getTime() > weekAgoMidnight) {
                        cal.setTime(sentAt);
                        timeBarDayText = Tools.TIME_WEEKDAYS_NAMES[cal.get(Calendar.DAY_OF_WEEK) - 1];
                    } else {
                        timeBarDayText = Tools.sdfDayOfWeek.format(sentAt);
                    }
                    timeBarDay.setText(timeBarDayText);
                    timeBarTime.setText(timeBarTimeText);
                    timeBar.setVisibility(View.VISIBLE);
                } else {
                    timeBar.setVisibility(View.GONE);
                }

                TextView textAvatar = (TextView) convertView.findViewById(R.id.atlas_view_messages_convert_initials);
                View spacerRight = convertView.findViewById(R.id.atlas_view_messages_convert_spacer_right);
                if (myMessage) {
                    spacerRight.setVisibility(View.GONE);
                    textAvatar.setVisibility(View.INVISIBLE);
                } else {
                    spacerRight.setVisibility(View.VISIBLE);
                    Atlas.Participant participant = participantProvider.getParticipant(userId);
                    String displayText = participant != null ? Atlas.getInitials(participant) : "";
                    textAvatar.setText(displayText);
                    textAvatar.setVisibility(View.VISIBLE);
                }

                // mark unsent messages
                View cellContainer = convertView.findViewById(R.id.atlas_view_messages_cell_container);
                cellContainer.setAlpha((myMessage && !cell.messagePart.getMessage().isSent())
                        ? CELL_CONTAINER_ALPHA_UNSENT : CELL_CONTAINER_ALPHA_SENT);

                // delivery receipt check
                TextView receiptView = (TextView) convertView.findViewById(R.id.atlas_view_messages_convert_delivery_receipt);
                receiptView.setVisibility(View.GONE);
                if (latestDeliveredMessage != null && latestDeliveredMessage.getId().equals(cell.messagePart.getMessage().getId())) {
                    receiptView.setVisibility(View.VISIBLE);
                    receiptView.setText("Delivered");
                }
                if (latestReadMessage != null && latestReadMessage.getId().equals(cell.messagePart.getMessage().getId())) {
                    receiptView.setVisibility(View.VISIBLE);
                    receiptView.setText("Read");
                }

                // processing cell
                bindCell(convertView, cell);

                // mark displayed message as read
                Message msg = part.getMessage();
                if (!client.getAuthenticatedUserId().equals(msg.getSender().getUserId())) {
                    msg.markAsRead();
                }

                timeBarDay.setTextColor(dateTextColor);
                timeBarTime.setTextColor(dateTextColor);
                textAvatar.setTextColor(avatarTextColor);
                ((GradientDrawable) textAvatar.getBackground()).setColor(avatarBackgroundColor);

                return convertView;
            }

            private void bindCell(View convertView, final Cell cell) {

                ViewGroup cellContainer = (ViewGroup) convertView.findViewById(R.id.atlas_view_messages_cell_container);

                View cellRootView = cell.onBind(cellContainer);
                boolean alreadyInContainer = false;
                // cleanUp container
                cellRootView.setVisibility(View.VISIBLE);
                for (int iChild = 0; iChild < cellContainer.getChildCount(); iChild++) {
                    View child = cellContainer.getChildAt(iChild);
                    if (child != cellRootView) {
                        child.setVisibility(View.GONE);
                    } else {
                        alreadyInContainer = true;
                    }
                }
                if (!alreadyInContainer) {
                    cellContainer.addView(cellRootView);
                }
            }

            public long getItemId(int position) {
                return position;
            }

            public AtlasMessagesList.Cell getItem(int position) {
                if (isReverseOrder)
                    return cells.get(cells.size() - position - 1);
                else
                    return cells.get(position);
            }

            public int getCount() {
                return cells.size();
            }

        });

        messagesList.setOnItemClickListener(new OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Cell item = cells.get(position);
                if (clickListener != null) {
                    clickListener.onItemClick(item);
                }
            }
        });
        // --- end of messageView

        updateValues();
    }

    public void parseStyle(Context context, AttributeSet attrs, int defStyle) {
        TypedArray ta = context.getTheme().obtainStyledAttributes(attrs, R.styleable.AtlasMessageList, R.attr.AtlasMessageList, defStyle);
        this.myTextColor = ta.getColor(R.styleable.AtlasMessageList_myTextColor, context.getResources().getColor(R.color.atlas_text_black));
        this.myTextStyle = ta.getInt(R.styleable.AtlasMessageList_myTextStyle, Typeface.NORMAL);
        String myTextTypefaceName = ta.getString(R.styleable.AtlasMessageList_myTextTypeface);
        this.myTextTypeface = myTextTypefaceName != null ? Typeface.create(myTextTypefaceName, myTextStyle) : null;
        //this.myTextSize = ta.getDimension(R.styleable.AtlasMessageList_myTextSize, context.getResources().getDimension(R.dimen.atlas_text_size_general));

        this.otherTextColor = ta.getColor(R.styleable.AtlasMessageList_theirTextColor, context.getResources().getColor(R.color.atlas_text_black));
        this.otherTextStyle = ta.getInt(R.styleable.AtlasMessageList_theirTextStyle, Typeface.NORMAL);
        String otherTextTypefaceName = ta.getString(R.styleable.AtlasMessageList_theirTextTypeface);
        this.otherTextTypeface = otherTextTypefaceName != null ? Typeface.create(otherTextTypefaceName, otherTextStyle) : null;
        //this.otherTextSize = ta.getDimension(R.styleable.AtlasMessageList_theirTextSize, context.getResources().getDimension(R.dimen.atlas_text_size_general));

        this.myBubbleColor = ta.getColor(R.styleable.AtlasMessageList_myBubbleColor, context.getResources().getColor(R.color.atlas_bubble_blue));
        this.otherBubbleColor = ta.getColor(R.styleable.AtlasMessageList_theirBubbleColor, context.getResources().getColor(R.color.atlas_background_gray));

        this.dateTextColor = ta.getColor(R.styleable.AtlasMessageList_dateTextColor, context.getResources().getColor(R.color.atlas_text_gray));
        this.avatarTextColor = ta.getColor(R.styleable.AtlasMessageList_avatarTextColor, context.getResources().getColor(R.color.atlas_text_black));
        this.avatarBackgroundColor = ta.getColor(R.styleable.AtlasMessageList_avatarBackgroundColor, context.getResources().getColor(R.color.atlas_background_gray));
        ta.recycle();
    }

    private void applyStyle() {
        messagesAdapter.notifyDataSetChanged();
    }

    protected void buildCellForMessage(Message msg, ArrayList<Cell> destination) {

        final ArrayList<MessagePart> parts = new ArrayList<MessagePart>(msg.getMessageParts());

        for (int partNo = 0; partNo < parts.size(); partNo++) {
            final MessagePart part = parts.get(partNo);
            final String mimeType = part.getMimeType();

            if (Atlas.MIME_TYPE_IMAGE_PNG.equals(mimeType) || Atlas.MIME_TYPE_IMAGE_JPEG.equals(mimeType)) {

                // 3 parts image support
                if ((partNo + 2 < parts.size()) && Atlas.MIME_TYPE_IMAGE_DIMENSIONS.equals(parts.get(partNo + 2).getMimeType())) {
                    String jsonDimensions = new String(parts.get(partNo + 2).getData());
                    try {
                        JSONObject jo = new JSONObject(jsonDimensions);
                        int orientation = jo.getInt("orientation");
                        int width = jo.getInt("width");
                        int height = jo.getInt("height");
                        if (orientation == 1 || orientation == 3) {
                            width = jo.getInt("height");
                            height = jo.getInt("width");
                        }
                        Cell imageCell = new ImageCell(part, parts.get(partNo + 1), width, height);
                        destination.add(imageCell);
                        if (debug)
                            Log.w(TAG, "cellForMessage() 3-image part found at partNo: " + partNo);
                        partNo++; // skip preview
                        partNo++; // skip dimensions part
                    } catch (JSONException e) {
                        Log.e(TAG, "cellForMessage() cannot parse 3-part image", e);
                    }
                } else {
                    // regular image
                    destination.add(new ImageCell(part));
                    if (debug)
                        Log.w(TAG, "cellForMessage() single-image part found at partNo: " + partNo);
                }

            } else if (Atlas.MIME_TYPE_ATLAS_LOCATION.equals(part.getMimeType())) {
                destination.add(new GeoCell(part));
            } else {
                Cell cellData = new TextCell(part);
                if (false && debug) Log.w(TAG, "cellForMessage() default item: " + cellData);
                destination.add(cellData);
            }
        }

    }

    public void updateValues() {
        if (conv == null) return;

        long started = System.currentTimeMillis();

        List<Message> messages = client.getMessages(conv);
        cells.clear();
        if (messages.isEmpty()) return;

        latestReadMessage = null;
        latestDeliveredMessage = null;

        ArrayList<Cell> messageItems = new ArrayList<AtlasMessagesList.Cell>();
        for (Message message : messages) {
            // System messages have `null` user ID
            if (!showSystemMessages && message.getSender().getUserId() == null) continue;

            messageItems.clear();
            buildCellForMessage(message, messageItems);
            cells.addAll(messageItems);
        }

        updateDeliveryStatus(messages);

        // calculate heads/tails
        int currentItem = 0;
        int clusterId = currentItem;
        String currentUser = null;
        long lastMessageTime = 0;
        Calendar calLastMessage = Calendar.getInstance();
        Calendar calCurrent = Calendar.getInstance();
        long clusterTimeSpan = 60 * 1000; // 1 minute
        long oneHourSpan = 60 * 60 * 1000; // 1 hour
        for (int i = 0; i < cells.size(); i++) {
            Cell item = cells.get(i);
            boolean newCluster = false;
            if (currentUser != null) {
                if (!currentUser.equals(item.messagePart.getMessage().getSender().getUserId())) {
                    newCluster = true;
                }
            }
            Date sentAt = item.messagePart.getMessage().getSentAt();
            if (sentAt == null) sentAt = new Date();

            if (sentAt.getTime() - lastMessageTime > clusterTimeSpan) {
                newCluster = true;
            }

            if (newCluster) {
                clusterId = currentItem;
                if (i > 0) cells.get(i - 1).clusterTail = true;
            }

            // check time header is needed
            if (sentAt.getTime() - lastMessageTime > oneHourSpan) {
                item.timeHeader = true;
            }
            calCurrent.setTime(sentAt);
            if (calCurrent.get(Calendar.DAY_OF_YEAR) != calLastMessage.get(Calendar.DAY_OF_YEAR)) {
                item.timeHeader = true;
            }

            item.clusterHeadItemId = clusterId;
            item.clusterItemId = currentItem++;

            currentUser = item.messagePart.getMessage().getSender().getUserId();
            lastMessageTime = sentAt.getTime();
            calLastMessage.setTime(sentAt);
            if (false && debug) Log.d(TAG, "updateValues() item: " + item);
        }
        cells.get(cells.size() - 1).clusterTail = true; // last one is always a tail

        if (debug)
            Log.d(TAG, "updateValues() parts finished in: " + (System.currentTimeMillis() - started));
        messagesAdapter.notifyDataSetChanged();

    }

    private boolean updateDeliveryStatus(List<Message> messages) {
        if (debug) Log.w(TAG, "updateDeliveryStatus() checking messages:   " + messages.size());
        Message oldLatestDeliveredMessage = latestDeliveredMessage;
        Message oldLatestReadMessage = latestReadMessage;
        // reset before scan
        latestDeliveredMessage = null;
        latestReadMessage = null;

        for (Message message : messages) {
            // only our messages
            if (client.getAuthenticatedUserId().equals(message.getSender().getUserId())) {
                if (!message.isSent()) continue;
                Map<String, RecipientStatus> statuses = message.getRecipientStatus();
                if (statuses == null || statuses.size() == 0) continue;
                for (Map.Entry<String, RecipientStatus> entry : statuses.entrySet()) {
                    // our read-status doesn't matter 
                    if (entry.getKey().equals(client.getAuthenticatedUserId())) continue;

                    if (entry.getValue() == RecipientStatus.READ) {
                        latestDeliveredMessage = message;
                        latestReadMessage = message;
                        break;
                    }
                    if (entry.getValue() == RecipientStatus.DELIVERED) {
                        latestDeliveredMessage = message;
                    }
                }
            }
        }
        boolean changed = false;
        if (oldLatestDeliveredMessage == null && latestDeliveredMessage != null) changed = true;
        else if (oldLatestDeliveredMessage != null && latestDeliveredMessage == null)
            changed = true;
        else if (oldLatestDeliveredMessage != null && latestDeliveredMessage != null
                && !oldLatestDeliveredMessage.getId().equals(latestDeliveredMessage.getId()))
            changed = true;

        if (oldLatestReadMessage == null && latestReadMessage != null) changed = true;
        else if (oldLatestReadMessage != null && latestReadMessage == null) changed = true;
        else if (oldLatestReadMessage != null && latestReadMessage != null
                && !oldLatestReadMessage.getId().equals(latestReadMessage.getId())) changed = true;

        if (debug)
            Log.w(TAG, "updateDeliveryStatus() read status changed: " + (changed ? "yes" : "no"));
        if (debug)
            Log.w(TAG, "updateDeliveryStatus() latestRead:          " + (latestReadMessage != null ? latestReadMessage.getSentAt() + ", id: " + latestReadMessage.getId() : "null"));
        if (debug)
            Log.w(TAG, "updateDeliveryStatus() latestDelivered:     " + (latestDeliveredMessage != null ? latestDeliveredMessage.getSentAt() + ", id: " + latestDeliveredMessage.getId() : "null"));

        return changed;
    }

    private long messageUpdateSentAt = 0;

    private final Handler refreshHandler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            long started = System.currentTimeMillis();
            if (msg.what == MESSAGE_TYPE_UPDATE_VALUES) {
                if (msg.arg1 == MESSAGE_REFRESH_UPDATE_ALL) {
                    updateValues();
                } else if (msg.arg1 == MESSAGE_REFRESH_UPDATE_DELIVERY) {
                    LayerClient client = (LayerClient) msg.obj;
                    boolean changed = updateDeliveryStatus(client.getMessages(conv));
                    if (changed) messagesAdapter.notifyDataSetInvalidated();
                    if (debug) Log.w(TAG, "refreshHandler() delivery status changed: " + changed);
                }
                if (msg.arg2 > 0) {
                    if (isReverseOrder)
                        messagesList.smoothScrollToPosition(0);
                    else
                        messagesList.smoothScrollToPosition(messagesAdapter.getCount() - 1);
                }
            }
            final long currentTimeMillis = System.currentTimeMillis();
            if (debug)
                Log.w(TAG, "handleMessage() delay: " + (currentTimeMillis - messageUpdateSentAt) + "ms, handled in: " + (currentTimeMillis - started) + "ms");
            messageUpdateSentAt = 0;
        }

    };

    @Override
    public void onEventMainThread(LayerChangeEvent event) {
        if (conv == null) return;
        boolean updateValues = false;
        boolean jumpToBottom = false;
        boolean updateDeliveryStatus = false;
        for (LayerChange change : event.getChanges()) {
            if (change.getObjectType() == LayerObject.Type.MESSAGE) {
                Message msg = (Message) change.getObject();
                if (msg.getConversation().getId().equals(conv.getId())) {
                    updateValues = true;
                    if (change.getChangeType() == Type.DELETE || change.getChangeType() == Type.INSERT) {
                        jumpToBottom = true;
                    }
                }
            }
            if (change.getChangeType() == Type.UPDATE && "recipientStatus".equals(change.getAttributeName())) {
                updateDeliveryStatus = true;
            }
        }

        if (updateValues || updateDeliveryStatus) {
            if (messageUpdateSentAt == 0) messageUpdateSentAt = System.currentTimeMillis();
            refreshHandler.removeMessages(MESSAGE_TYPE_UPDATE_VALUES);
            final android.os.Message message = refreshHandler.obtainMessage();
            message.arg1 = updateValues ? MESSAGE_REFRESH_UPDATE_ALL : MESSAGE_REFRESH_UPDATE_DELIVERY;
            message.arg2 = jumpToBottom ? 1 : 0;
            message.obj = event.getClient();
            refreshHandler.sendMessage(message);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (debug) Log.d(TAG, "onDetachedFromWindow() clean cells and views... ");
        cells.clear();
        messagesAdapter.notifyDataSetChanged();
        messagesList.removeAllViewsInLayout();
    }

    public void jumpToLastMessage() {
        if (isReverseOrder)
            messagesList.smoothScrollToPosition(0);
        else
            messagesList.smoothScrollToPosition(cells.size() - 1);
    }

    public Conversation getConversation() {
        return conv;
    }

    public void setConversation(Conversation conv) {
        this.conv = conv;
        updateValues();
        jumpToLastMessage();
    }

    public void setItemClickListener(ItemClickListener clickListener) {
        this.clickListener = clickListener;
    }

    public void setReverseOrder(boolean reverseOrder) {
        this.isReverseOrder = reverseOrder;
    }

    public ListView getListView() {
        return messagesList;
    }

    public void setShowSystemMessages(boolean showSystemMessages) {
        this.showSystemMessages = showSystemMessages;
    }

    private class GeoCell extends Cell implements DownloadQueue.CompleteListener {

        double lon;
        double lat;

        ImageSpec spec;

        public GeoCell(MessagePart messagePart) {
            super(messagePart);
            String jsonLonLat = new String(messagePart.getData());
            try {
                JSONObject json = new JSONObject(jsonLonLat);
                this.lon = json.getDouble("lon");
                this.lat = json.getDouble("lat");
            } catch (JSONException e) {
                throw new IllegalArgumentException("Wrong geoJSON format: " + jsonLonLat, e);
            }
        }

        @Override
        public View onBind(final ViewGroup cellContainer) {

            ViewGroup cellRoot = (ViewGroup) Tools.findChildById(cellContainer, R.id.atlas_view_messages_cell_geo);
            if (cellRoot == null) {
                cellRoot = (ViewGroup) LayoutInflater.from(cellContainer.getContext()).inflate(R.layout.atlas_view_messages_cell_geo, cellContainer, false);
                if (debug) Log.w(TAG, "geo.onBind() inflated geo cell");
            }

            ImageView geoImageMy = (ImageView) cellRoot.findViewById(R.id.atlas_view_messages_cell_geo_image_my);
            ImageView geoImageTheir = (ImageView) cellRoot.findViewById(R.id.atlas_view_messages_cell_geo_image_their);
            View containerMy = cellRoot.findViewById(R.id.atlas_view_messages_cell_geo_container_my);
            View containerTheir = cellRoot.findViewById(R.id.atlas_view_messages_cell_geo_container_their);

            boolean myMessage = client.getAuthenticatedUserId().equals(messagePart.getMessage().getSender().getUserId());
            if (myMessage) {
                containerMy.setVisibility(View.VISIBLE);
                containerTheir.setVisibility(View.GONE);
            } else {
                containerMy.setVisibility(View.GONE);
                containerTheir.setVisibility(View.VISIBLE);
            }
            ImageView geoImage = myMessage ? geoImageMy : geoImageTheir;
            ShapedFrameLayout cellCustom = (ShapedFrameLayout) (myMessage ? containerMy : containerTheir);

            Object imageId = messagePart.getId();
            Bitmap bmp = imageLoader.getBitmapFromCache(imageId);
            if (bmp != null) {
                if (debug)
                    Log.d(TAG, "geo.onBind() bitmap: " + bmp.getWidth() + "x" + bmp.getHeight());
                geoImage.setImageBitmap(bmp);
            } else {
                if (debug) Log.d(TAG, "geo.onBind() spec: " + spec);
                geoImage.setImageDrawable(EMPTY_DRAWABLE);
                // schedule image
                File tileFile = getTileFile();
                if (tileFile.exists()) {
                    if (debug) Log.d(TAG, "geo.onBind() decodeImage: " + tileFile);
                    // request decoding
                    spec = imageLoader.requestBitmap(imageId
                            , new ImageLoader.FileStreamProvider(tileFile)
                            , (int) Tools.getPxFromDp(150, getContext())
                            , (int) Tools.getPxFromDp(150, getContext()), BITMAP_LOAD_LISTENER);
                } else {
                    int width = 300;
                    int height = 300;
                    int zoom = 16;
                    final String url = new StringBuilder()
                            .append("https://maps.googleapis.com/maps/api/staticmap?")
                            .append("format=png32&")
                            .append("center=").append(lat).append(",").append(lon).append("&")
                            .append("zoom=").append(zoom).append("&")
                            .append("size=").append(width).append("x").append(height).append("&")
                            .append("maptype=roadmap&")
                            .append("markers=color:red%7C").append(lat).append(",").append(lon)
                            .toString();

                    downloadQueue.schedule(url, tileFile, this);

                    if (debug) Log.d(TAG, "geo.onBind() show stub and download image: " + tileFile);
                }
            }

            Cell cell = this;
            // clustering
            cellCustom.setCornerRadiusDp(16, 16, 16, 16);
            if (CLUSTERED_BUBBLES) {
                if (myMessage) {
                    if (cell.clusterHeadItemId == cell.clusterItemId && !cell.clusterTail) {
                        cellCustom.setCornerRadiusDp(16, 16, 2, 16);
                    } else if (cell.clusterTail && cell.clusterHeadItemId != cell.clusterItemId) {
                        cellCustom.setCornerRadiusDp(16, 2, 16, 16);
                    } else if (cell.clusterHeadItemId != cell.clusterItemId && !cell.clusterTail) {
                        cellCustom.setCornerRadiusDp(16, 2, 2, 16);
                    }
                } else {
                    if (cell.clusterHeadItemId == cell.clusterItemId && !cell.clusterTail) {
                        cellCustom.setCornerRadiusDp(16, 16, 16, 2);
                    } else if (cell.clusterTail && cell.clusterHeadItemId != cell.clusterItemId) {
                        cellCustom.setCornerRadiusDp(2, 16, 16, 16);
                    } else if (cell.clusterHeadItemId != cell.clusterItemId && !cell.clusterTail) {
                        cellCustom.setCornerRadiusDp(2, 16, 16, 2);
                    }
                }
            }

            return cellRoot;
        }

        private File getTileFile() {
            String fileDir = getContext().getCacheDir() + File.separator + "geo";
            String fileName = String.format("%f_%f.png", lat, lon);
            return new File(fileDir, fileName);
        }

        @Override
        public String toString() {
            final String text = "Location:\nlon: " + lon + "\nlat: " + lat;
            return text + " part: " + super.toString();
        }

        @Override
        public void onDownloadComplete(String url, final File file) {
            postViewRefresh();
        }
    }

    private static boolean downloadToFile(String url, File file) {
        HttpGet get = new HttpGet(url);
        HttpResponse response;
        try {
            response = (new DefaultHttpClient()).execute(get);
            if (HttpStatus.SC_OK != response.getStatusLine().getStatusCode()) {
                Log.e(TAG, String.format("Expected status 200, but got %d", response.getStatusLine().getStatusCode()));
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "downloadToFile() cannot execute http request: " + url, e);
            return false;
        }

        File dir = file.getParentFile();
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, String.format("Could not create directories for `%s`", dir.getAbsolutePath()));
            return false;
        }

        File tempFile = new File(file.getAbsolutePath() + ".tmp");

        try {
            Tools.streamCopyAndClose(response.getEntity().getContent(), new FileOutputStream(tempFile, false));
            response.getEntity().consumeContent();
        } catch (IOException e) {
            if (debug)
                Log.e(TAG, "downloadToFile() cannot extract content from http response: " + url, e);
        }

        if (tempFile.length() != response.getEntity().getContentLength()) {
            tempFile.delete();
            Log.e(TAG, String.format("downloadToFile() File size mismatch for `%s` (%d vs %d)", tempFile.getAbsolutePath(), tempFile.length(), response.getEntity().getContentLength()));
            return false;
        }

        // last step
        if (tempFile.renameTo(file)) {
            if (debug)
                Log.w(TAG, "downloadToFile() Successfully downloaded file: " + file.getAbsolutePath());
            return true;
        } else {
            Log.e(TAG, "downloadToFile() Could not rename temp file: " + tempFile.getAbsolutePath() + " to: " + file.getAbsolutePath());
            return false;
        }

    }

    private static class DownloadQueue {
        private static final String TAG = DownloadQueue.class.getSimpleName();

        final ArrayList<Entry> queue = new ArrayList<AtlasMessagesList.DownloadQueue.Entry>();
        final HashMap<String, Entry> url2Entry = new HashMap<String, Entry>();
        private volatile Entry inProgress = null;

        public DownloadQueue() {
            workingThread.setDaemon(true);
            workingThread.setName("Atlas-HttpDownloadQueue");
            workingThread.start();
        }

        public void schedule(String url, File to, CompleteListener onComplete) {
            if (inProgress != null && inProgress.url.equals(url)) {
                return;
            }
            synchronized (queue) {
                Entry existing = url2Entry.get(url);
                if (existing != null) {
                    queue.remove(existing);
                    queue.add(existing);
                } else {
                    Entry toSchedule = new Entry(url, to, onComplete);
                    queue.add(toSchedule);
                    url2Entry.put(toSchedule.url, toSchedule);
                }
                queue.notifyAll();
            }
        }

        private Thread workingThread = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    Entry next = null;
                    synchronized (queue) {
                        while (queue.size() == 0) {
                            try {
                                queue.wait();
                            } catch (InterruptedException ignored) {
                            }
                        }
                        next = queue.remove(queue.size() - 1); // get last
                        url2Entry.remove(next.url);
                        inProgress = next;
                    }
                    try {
                        if (downloadToFile(next.url, next.file)) {
                            if (next.completeListener != null) {
                                next.completeListener.onDownloadComplete(next.url, next.file);
                            }
                        }
                        ;
                    } catch (Throwable e) {
                        Log.e(TAG, "onComplete() thrown an exception for: " + next.url, e);
                    }
                    inProgress = null;
                }
            }
        });

        private static class Entry {
            String url;
            File file;
            CompleteListener completeListener;

            public Entry(String url, File file, CompleteListener listener) {
                if (url == null) throw new IllegalArgumentException("url cannot be null");
                if (file == null) throw new IllegalArgumentException("file cannot be null");
                this.url = url;
                this.file = file;
                this.completeListener = listener;
            }
        }

        public interface CompleteListener {
            public void onDownloadComplete(String url, File file);
        }
    }

    private class TextCell extends Cell {

        protected String text;

        public TextCell(MessagePart messagePart) {
            super(messagePart);
        }

        public TextCell(MessagePart messagePart, String text) {
            super(messagePart);
            this.text = text;
        }

        public View onBind(ViewGroup cellContainer) {
            MessagePart part = messagePart;
            Cell cell = this;

            View cellText = Tools.findChildById(cellContainer, R.id.atlas_view_messages_cell_text);
            if (cellText == null) {
                cellText = LayoutInflater.from(cellContainer.getContext()).inflate(R.layout.atlas_view_messages_cell_text, cellContainer, false);
            }

            if (text == null) {
                if (Atlas.MIME_TYPE_TEXT.equals(part.getMimeType())) {
                    text = new String(part.getData());
                } else {
                    text = "attach, type: " + part.getMimeType() + ", size: " + part.getSize();
                }
            }

            boolean myMessage = client.getAuthenticatedUserId().equals(cell.messagePart.getMessage().getSender().getUserId());
            TextView textMy = (TextView) cellText.findViewById(R.id.atlas_view_messages_convert_text);
            TextView textOther = (TextView) cellText.findViewById(R.id.atlas_view_messages_convert_text_counterparty);
            if (myMessage) {
                textMy.setVisibility(View.VISIBLE);
                textMy.setText(text);
                textOther.setVisibility(View.GONE);

                textMy.setBackgroundResource(R.drawable.atlas_shape_rounded16_blue);

                if (CLUSTERED_BUBBLES) {
                    if (cell.clusterHeadItemId == cell.clusterItemId && !cell.clusterTail) {
                        textMy.setBackgroundResource(R.drawable.atlas_shape_rounded16_blue_no_bottom_right);
                    } else if (cell.clusterTail && cell.clusterHeadItemId != cell.clusterItemId) {
                        textMy.setBackgroundResource(R.drawable.atlas_shape_rounded16_blue_no_top_right);
                    } else if (cell.clusterHeadItemId != cell.clusterItemId && !cell.clusterTail) {
                        textMy.setBackgroundResource(R.drawable.atlas_shape_rounded16_blue_no_right);
                    }
                }
                ((GradientDrawable) textMy.getBackground()).setColor(myBubbleColor);
                textMy.setTextColor(myTextColor);
                //textMy.setTextSize(TypedValue.COMPLEX_UNIT_DIP, myTextSize);
                textMy.setTypeface(myTextTypeface, myTextStyle);
            } else {
                textOther.setVisibility(View.VISIBLE);
                textOther.setText(text);
                textMy.setVisibility(View.GONE);

                textOther.setBackgroundResource(R.drawable.atlas_shape_rounded16_gray);
                if (CLUSTERED_BUBBLES) {
                    if (cell.clusterHeadItemId == cell.clusterItemId && !cell.clusterTail) {
                        textOther.setBackgroundResource(R.drawable.atlas_shape_rounded16_gray_no_bottom_left);
                    } else if (cell.clusterTail && cell.clusterHeadItemId != cell.clusterItemId) {
                        textOther.setBackgroundResource(R.drawable.atlas_shape_rounded16_gray_no_top_left);
                    } else if (cell.clusterHeadItemId != cell.clusterItemId && !cell.clusterTail) {
                        textOther.setBackgroundResource(R.drawable.atlas_shape_rounded16_gray_no_left);
                    }
                }
                ((GradientDrawable) textOther.getBackground()).setColor(otherBubbleColor);
                textOther.setTextColor(otherTextColor);
                //textOther.setTextSize(TypedValue.COMPLEX_UNIT_DIP, otherTextSize);
                textOther.setTypeface(otherTextTypeface, otherTextStyle);
            }
            return cellText;
        }
    }

    private static final BitmapDrawable EMPTY_DRAWABLE = new BitmapDrawable(Bitmap.createBitmap(new int[]{Color.TRANSPARENT}, 1, 1, Bitmap.Config.ALPHA_8));

    private class ImageCell extends Cell implements LayerProgressListener {
        MessagePart previewPart;
        MessagePart fullPart;
        int width;
        int height;
        ImageLoader.ImageSpec imageSpec;

        private ImageCell(MessagePart fullImagePart) {
            super(fullImagePart);
            this.fullPart = fullImagePart;
        }

        private ImageCell(MessagePart fullImagePart, MessagePart previewImagePart, int width, int height) {
            super(fullImagePart);
            this.fullPart = fullImagePart;
            this.previewPart = previewImagePart;
            this.width = width;
            this.height = height;
        }

        @Override
        public View onBind(final ViewGroup cellContainer) {
            View rootView = Tools.findChildById(cellContainer, R.id.atlas_view_messages_cell_image);
            if (rootView == null) {
                rootView = LayoutInflater.from(cellContainer.getContext()).inflate(R.layout.atlas_view_messages_cell_image, cellContainer, false);
            }

            Cell cell = this;
            boolean myMessage = client.getAuthenticatedUserId().equals(cell.messagePart.getMessage().getSender().getUserId());

            View imageContainerMy = rootView.findViewById(R.id.atlas_view_messages_cell_image_container_my);
            View imageContainerOther = rootView.findViewById(R.id.atlas_view_messages_cell_image_container_their);
            ImageView imageViewMy = (ImageView) imageContainerMy.findViewById(R.id.atlas_view_messages_cell_image_my);
            ImageView imageViewOther = (ImageView) imageContainerOther.findViewById(R.id.atlas_view_messages_cell_image_their);
            ImageView imageView = myMessage ? imageViewMy : imageViewOther;
            View imageContainer = myMessage ? imageContainerMy : imageContainerOther;

            if (myMessage) {
                imageContainerMy.setVisibility(View.VISIBLE);
                imageContainerOther.setVisibility(View.GONE);
            } else {
                imageContainerMy.setVisibility(View.GONE);
                imageContainerOther.setVisibility(View.VISIBLE);
            }

            // get BitmapDrawable

            int requiredWidth = /*imageContainer.getWidth() > 0 ? imageContainer.getWidth() :*/ messagesList.getWidth();
            int requiredHeight = /*imageContainer.getHeight() > 0 ? imageContainer.getHeight() : */messagesList.getHeight();
            MessagePart workingPart = previewPart != null ? previewPart : fullPart;
            Bitmap bmp = imageLoader.getBitmapFromCache(workingPart.getId());

            //adjust width/height
            int width = this.width;
            int height = this.height;
            if ((width == 0 || height == 0) && imageSpec != null && imageSpec.originalWidth != 0) {
                if (debug)
                    Log.w(TAG, "img.onBind() size from spec:   " + imageSpec.originalWidth + "x" + imageSpec.originalHeight);
                width = imageSpec.originalWidth;
                height = imageSpec.originalHeight;
            }
            if ((width == 0 || height == 0) && bmp != null) {
                width = bmp.getWidth();
                height = bmp.getHeight();
                if (debug)
                    Log.w(TAG, "img.onBind() size from bitmap: " + bmp.getWidth() + "x" + bmp.getHeight());
            }

            int viewWidth = (int) (width != 0 ? width : Tools.getPxFromDp(48 * 4, imageContainer.getContext()));
            int viewHeight = (int) (height != 0 ? height : Tools.getPxFromDp(48 * 4, imageContainer.getContext()));
            int widthToFit = 0;
            imageContainer.getWidth();
            if (widthToFit == 0) widthToFit = cellContainer.getWidth();
            if (widthToFit == 0) widthToFit = messagesList.getWidth();
            if (viewWidth > widthToFit) {
                viewHeight = (int) (1.0 * viewHeight * widthToFit / viewWidth);
                viewWidth = widthToFit;
            }
            if (viewHeight > messagesList.getHeight() && messagesList.getHeight() > 0) {
                viewWidth = (int) (1.0 * viewWidth * messagesList.getHeight() / viewHeight);
                viewHeight = messagesList.getHeight();
            }
            if (debug) Log.w(TAG, "img.onBind() view size: " + viewWidth + "x" + viewHeight
                    + ", container: " + (myMessage ? "my " : "their ") + imageContainer.getWidth() + "x" + imageContainer.getHeight()
                    + ", cell: " + cellContainer.getWidth() + "x" + cellContainer.getHeight()
                    + ", image: " + width + "x" + height);

            imageView.getLayoutParams().width = viewWidth;
            imageView.getLayoutParams().height = viewHeight;

            if (bmp != null) {
                imageView.setImageBitmap(bmp);
                if (debug)
                    Log.i(TAG, "img.onBind() returned from cache! " + bmp.getWidth() + "x" + bmp.getHeight() + " " + bmp.getByteCount() + " bytes" + " req: " + requiredWidth + "x" + requiredHeight + " for " + messagePart.getId());
            } else {
                imageView.setImageDrawable(EMPTY_DRAWABLE);
                final Uri id = workingPart.getId();
                final MessagePartStreamProvider streamProvider = new MessagePartStreamProvider(workingPart);
                if (workingPart.isContentReady()) {
                    imageSpec = imageLoader.requestBitmap(id, streamProvider, requiredWidth, requiredHeight, BITMAP_LOAD_LISTENER);
                } else {
                    workingPart.download(this);
                }
            }

            ShapedFrameLayout cellCustom = (ShapedFrameLayout) (myMessage ? imageContainerMy : imageContainerOther);
            // clustering
            cellCustom.setCornerRadiusDp(16, 16, 16, 16);
            if (!CLUSTERED_BUBBLES) return rootView;
            if (myMessage) {
                if (cell.clusterHeadItemId == cell.clusterItemId && !cell.clusterTail) {
                    cellCustom.setCornerRadiusDp(16, 16, 2, 16);
                } else if (cell.clusterTail && cell.clusterHeadItemId != cell.clusterItemId) {
                    cellCustom.setCornerRadiusDp(16, 2, 16, 16);
                } else if (cell.clusterHeadItemId != cell.clusterItemId && !cell.clusterTail) {
                    cellCustom.setCornerRadiusDp(16, 2, 2, 16);
                }
            } else {
                if (cell.clusterHeadItemId == cell.clusterItemId && !cell.clusterTail) {
                    cellCustom.setCornerRadiusDp(16, 16, 16, 2);
                } else if (cell.clusterTail && cell.clusterHeadItemId != cell.clusterItemId) {
                    cellCustom.setCornerRadiusDp(2, 16, 16, 16);
                } else if (cell.clusterHeadItemId != cell.clusterItemId && !cell.clusterTail) {
                    cellCustom.setCornerRadiusDp(2, 16, 16, 2);
                }
            }
            return rootView;
        }

        // LayerDownloadListener (when downloading part)
        public void onProgressStart(MessagePart part, Operation operation) {
        }

        public void onProgressUpdate(MessagePart part, Operation operation, long transferredBytes) {
        }

        public void onProgressError(MessagePart part, Operation operation, Throwable cause) {
        }

        public void onProgressComplete(MessagePart part, Operation operation) {
            postViewRefresh();
        }
    }

    private void postViewRefresh() {
        messagesList.post(INVALIDATE_VIEW);
    }

    private final Runnable INVALIDATE_VIEW = new Runnable() {
        public void run() {
            messagesList.invalidateViews();
        }
    };

    private final BitmapLoadListener BITMAP_LOAD_LISTENER = new ImageLoader.BitmapLoadListener() {
        public void onBitmapLoaded(ImageSpec spec) {
            postViewRefresh();
        }
    };

    private static final Atlas.ImageLoader imageLoader = new Atlas.ImageLoader();
    private static final DownloadQueue downloadQueue = new DownloadQueue();

    public abstract class Cell {
        public final MessagePart messagePart;
        private int clusterHeadItemId;
        private int clusterItemId;
        private boolean clusterTail;
        private boolean timeHeader;

        public Cell(MessagePart messagePart) {
            this.messagePart = messagePart;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("[ ")
                    .append("messagePart: ").append(messagePart.getMimeType())
                    .append(": ").append(messagePart.getSize() < 2048 ? new String(messagePart.getData()) : messagePart.getSize() + " bytes")
                    .append(", clusterId: ").append(clusterHeadItemId)
                    .append(", clusterItem: ").append(clusterItemId)
                    .append(", clusterTail: ").append(clusterTail)
                    .append(", timeHeader: ").append(timeHeader).append(" ]");
            return builder.toString();
        }

        public abstract View onBind(ViewGroup cellContainer);
    }


    public interface ItemClickListener {
        void onItemClick(Cell item);
    }

}
