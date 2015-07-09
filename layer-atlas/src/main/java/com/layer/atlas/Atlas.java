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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;

import com.layer.sdk.messaging.Conversation;
import com.layer.sdk.messaging.Message;
import com.layer.sdk.messaging.MessagePart;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author Oleg Orlov
 * @since 12 May 2015
 */
public class Atlas {

    public static final String METADATA_KEY_CONVERSATION_TITLE = "conversationName";
    
    public static final String MIME_TYPE_ATLAS_LOCATION = "location/coordinate";
    public static final String MIME_TYPE_TEXT = "text/plain";
    public static final String MIME_TYPE_IMAGE_JPEG = "image/jpeg";
    public static final String MIME_TYPE_IMAGE_JPEG_PREVIEW = "image/jpeg+preview";
    public static final String MIME_TYPE_IMAGE_PNG = "image/png";
    public static final String MIME_TYPE_IMAGE_PNG_PREVIEW = "image/png+preview";
    public static final String MIME_TYPE_IMAGE_DIMENSIONS = "application/json+imageSize";

    public static String getInitials(Participant p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.getFirstName() != null && p.getFirstName().trim().length() > 0 ? p.getFirstName().trim().charAt(0) : "");
        sb.append(p.getLastName() != null && p.getLastName().trim().length() > 0 ? p.getLastName().trim().charAt(0) : "");
        return sb.toString();
    }

    public static String getFirstNameLastInitial(Participant p) {
        StringBuilder sb = new StringBuilder();
        if (p.getFirstName() != null && p.getFirstName().trim().length() > 0) {
            sb.append(p.getFirstName().trim());
        }
        if (p.getLastName() != null && p.getLastName().trim().length() > 0) {
            sb.append(" ").append(p.getLastName().trim().charAt(0));
            sb.append(".");
        }
        return sb.toString();
    }

    public static String getFullName(Participant p) {
        StringBuilder sb = new StringBuilder();
        if (p.getFirstName() != null && p.getFirstName().trim().length() > 0) {
            sb.append(p.getFirstName().trim());
    }
        if (p.getLastName() != null && p.getLastName().trim().length() > 0) {
            sb.append(" ").append(p.getLastName().trim());
        }
        return sb.toString();
    }

    public static void setTitle(Conversation conversation, String title) {
        conversation.putMetadataAtKeyPath(Atlas.METADATA_KEY_CONVERSATION_TITLE, title);
    }

    public static String getTitle(Conversation conversation) {
        return (String) conversation.getMetadata().get(Atlas.METADATA_KEY_CONVERSATION_TITLE);
    }

    public static String getTitle(Conversation conversation, ParticipantProvider provider, String userId) {
        String conversationTitle = getTitle(conversation);
        if (conversationTitle != null && conversationTitle.trim().length() > 0) return conversationTitle.trim();

        StringBuilder sb = new StringBuilder();
        for (String participantId : conversation.getParticipants()) {
            if (participantId.equals(userId)) continue;
            Participant participant = provider.getParticipant(participantId);
            if (participant == null) continue;
            String initials = conversation.getParticipants().size() > 2 ? getFirstNameLastInitial(participant) : getFullName(participant);
            if (sb.length() > 0) sb.append(", ");
            sb.append(initials);
        }
        return sb.toString().trim();
    }

    /**
     * Participant allows Atlas classes to display information about users, like Message senders,
     * Conversation participants, TypingIndicator users, etc.
     */
    public interface Participant {
        Comparator<Participant> COMPARATOR = new FilteringComparator("");

        /**
         * Returns the first name of this Participant.
         *
         * @return The first name of this Participant
         */
        String getFirstName();

        /**
         * Returns the last name of this Participant.
         *
         * @return The last name of this Participant
         */
        String getLastName();
    }

    /**
     * ParticipantProvider provides Atlas classes with Participant data.
     */
    public interface ParticipantProvider {
        /**
         * Returns a map of all Participants by their unique ID who match the provided `filter`, or
         * all Participants if `filter` is `null`.  If `result` is provided, it is operated on and
         * returned.  If `result` is `null`, a new Map is created and returned.
         *
         * @param filter The filter to apply to Participants
         * @param result The Map to operate on
         * @return A Map of all matching Participants keyed by ID.
         */
        Map<String, Participant> getParticipants(String filter, Map<String, Participant> result);

        /**
         * Returns the Participant with the given ID, or `null` if the participant is not yet
         * available.
         *
         * @return The Participant with the given ID, or `null` if not available.
         */
        Atlas.Participant getParticipant(String userId);
    }

    public static final class Tools {
        /** Millis in 24 Hours */
        public static final int TIME_HOURS_24 = 24 * 60 * 60 * 1000;
        // TODO: localization required to all time based constants below
        public static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm a");
        public static final SimpleDateFormat sdfDayOfWeek = new SimpleDateFormat("EEE, LLL dd,");
        /** Ensure you decrease value returned by Calendar.get(Calendar.DAY_OF_WEEK) by 1. Calendar's days starts from 1. */
        public static final String[] TIME_WEEKDAYS_NAMES = new String[] {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};

        public static String toString(Message msg) {
            StringBuilder sb = new StringBuilder();
            for (MessagePart mp : msg.getMessageParts()) {
                if (MIME_TYPE_TEXT.equals(mp.getMimeType())) {
                    sb.append(new String(mp.getData()));
                } else if (MIME_TYPE_ATLAS_LOCATION.equals(mp.getMimeType())){
                    sb.append("Attachemnt: Location");
                } else {
                    sb.append("Attachment: Image");
                    break;
                }
            }
            return sb.toString();
        }

        public static float[] getRoundRectRadii(float[] cornerRadiusDp, final DisplayMetrics displayMetrics) {
            float[] result = new float[8];
            for (int i = 0; i < cornerRadiusDp.length; i++) {
                result[i * 2] = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, cornerRadiusDp[i], displayMetrics);
                result[i * 2 + 1] = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, cornerRadiusDp[i], displayMetrics);
            }
            return result;
        }

        public static float getPxFromDp(float dp, Context context) {
            return getPxFromDp(dp, context.getResources().getDisplayMetrics());
        }

        public static float getPxFromDp(float dp, DisplayMetrics displayMetrics) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, displayMetrics);
        }

        public static View findChildById(ViewGroup group, int id) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getId() == id) return child;
            }
            return null;
        }

        public static void closeQuietly(InputStream stream) {
            if (stream == null) return;
            try {
                stream.close();
            } catch (Throwable ignoredQueitly) {}
        }

        public static void closeQuietly(OutputStream stream) {
            if (stream == null) return;
            try {
                stream.close();
            } catch (Throwable ignoredQueitly) {}
        }

        /**
         * @return number of copied bytes
         */
        public static int streamCopyAndClose(InputStream from, OutputStream to) throws IOException {
            int totalBytes = streamCopy(from, to);
            from.close();
            to.close();
            return totalBytes;
        }

        /**
         * @return number of copied bytes
         */
        public static int streamCopy(InputStream from, OutputStream to) throws IOException {
            byte[] buffer = new byte[65536];
            int bytesRead = 0;
            int totalBytes = 0;
            for (; (bytesRead = from.read(buffer)) != -1; totalBytes += bytesRead) {
                to.write(buffer, 0, bytesRead);
            }
            return totalBytes;
        }

        public static String toStringSpec(int measureSpec) {
            switch (MeasureSpec.getMode(measureSpec)) {
                case MeasureSpec.AT_MOST:
                    return "" + MeasureSpec.getSize(measureSpec) + ":A";
                case MeasureSpec.EXACTLY : return "" + MeasureSpec.getSize(measureSpec) + ":E";
                default                  : return "" + MeasureSpec.getSize(measureSpec) + ":U";
            }
        }
    }

    public static final class FilteringComparator implements Comparator<Atlas.Participant> {
        private final String filter;
    
        /**
         * @param filter - the less indexOf(filter) the less order of participant
         */
        public FilteringComparator(String filter) {
            this.filter = filter;
        }
    
        @Override
        public int compare(Atlas.Participant lhs, Atlas.Participant rhs) {
            int result = subCompareCaseInsensitive(lhs.getFirstName(), rhs.getFirstName());
            if (result != 0) return result;
            return subCompareCaseInsensitive(lhs.getLastName(), rhs.getLastName());
        }
    
        private int subCompareCaseInsensitive(String lhs, String rhs) {
            int left = lhs != null ? lhs.toLowerCase().indexOf(filter) : -1;
            int right = rhs != null ? rhs.toLowerCase().indexOf(filter) : -1;
    
            if (left == -1 && right == -1) return 0;
            if (left != -1 && right == -1) return -1;
            if (left == -1 && right != -1) return 1;
            if (left - right != 0) return left - right;
            return String.CASE_INSENSITIVE_ORDER.compare(lhs, rhs);
        }
    }

    /**
     * TODO: 
     * 
     * - imageCache should accept any "Downloader" that download something with progress 
     * - imageCache should reschedule image if decoding failed
     * - imageCache should reschedule image if decoded width was cut due to OOM (-> sampleSize > 1) 
     * - maximum retries should be configurable
     * 
     */
    public static class ImageLoader {
        private static final String TAG = Atlas.ImageLoader.class.getSimpleName();
        private static final boolean debug = false;
        
        private static final int BITMAP_DECODE_RETRIES = 10;
        private static final double MEMORY_THRESHOLD = 0.7;
        private final Thread processingThread;
        private final Object lock = new Object();
        private final ArrayList<ImageSpec> queue = new ArrayList<ImageSpec>();
        private volatile boolean shutdownLoader = false;
        private LinkedHashMap<Object, Bitmap> cache = new LinkedHashMap<Object, Bitmap>(40, 1f, true) {
            private static final long serialVersionUID = 1L;
            protected boolean removeEldestEntry(Entry<Object, Bitmap> eldest) {
                // calculate available memory
                long maxMemory = Runtime.getRuntime().maxMemory();
                long usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                boolean cleaningRequired = 1.0 * usedMemory / maxMemory > MEMORY_THRESHOLD; 
                
                final Object id = eldest.getKey();
                if (cleaningRequired) if (debug) Log.w(TAG, "removeEldestEntry() cleaning bitmap for: " + id + ", size: " + cache.size() + ", queue: " + queue.size());
                else                  if (debug) Log.w(TAG, "removeEldestEntry() " + " nothing, size: " + cache.size() + ", queue: " + queue.size());                    
    
                return cleaningRequired;
            }
        };
    
        public ImageLoader() {
            // launching thread
            processingThread = new Decoder("AtlasImageLoader"); 
            processingThread.start();
        }
        
        public Bitmap getBitmapFromCache(Object id) {
            return cache.get(id);
        }

        /**
         * @return - byteCount of removed bitmap if bitmap found. <bold>-1</bold> otherwise
         */
        private int removeEldest() {
            synchronized (lock) {
                if (cache.size() > 0) {
                    Map.Entry<Object, Bitmap> entry = cache.entrySet().iterator().next();
                    Bitmap bmp = entry.getValue();
                    cache.remove(entry.getKey());
                    if (debug) Log.w(TAG, "removeEldest() id: " + entry.getKey() + ", bytes: " + bmp.getByteCount());
                    return bmp.getByteCount();
                } else {
                    if (debug) Log.w(TAG, "removeEldest() nothing to remove...");
                    return -1;
                }
            }
        }
                
        public ImageSpec requestBitmap(Object id, StreamProvider streamProvider, int requiredWidth, int requiredHeight, ImageLoader.BitmapLoadListener loadListener) {
            ImageSpec spec = null;
            synchronized (lock) {
                for (int i = 0; i < queue.size(); i++) {
                    if (queue.get(i).id.equals(id)) {
                        spec = queue.remove(i);
                        break;
                    }
                }
                if (spec == null) {
                    spec = new ImageSpec();
                    spec.id = id;
                    spec.inputStreamProvider = streamProvider;
                    spec.requiredHeight = requiredHeight;
                    spec.requiredWidth = requiredWidth;
                    spec.listener = loadListener;
                }
                queue.add(0, spec);
                lock.notifyAll();
            }
            if (debug) Log.w(TAG, "requestBitmap() cache: " + cache.size() + ", queue: " + queue.size() + ", id: " + id);
            return spec;
        }

        public static class ImageSpec {
            public Object id;
            public StreamProvider inputStreamProvider;
            public int requiredWidth;
            public int requiredHeight;
            public int originalWidth;
            public int originalHeight;
            public int downloadProgress;
            public int retries = 0;
            public ImageLoader.BitmapLoadListener listener;
        }

        public static abstract class BitmapLoadListener {
            public abstract void onBitmapLoaded(ImageSpec spec);
        }

        public static abstract class StreamProvider {
            public abstract InputStream getInputStream();
            public abstract boolean ready();
        }
        
        public static class MessagePartStreamProvider extends StreamProvider {
            public final MessagePart part;
            public MessagePartStreamProvider(MessagePart part) {
                if (part == null) throw new IllegalStateException("MessagePart cannot be null");
                this.part = part;
            }
            public InputStream getInputStream() {
                return part.getDataStream();
            }
            public boolean ready() {
                return part.isContentReady();
            }
        }
        
        public static class FileStreamProvider extends StreamProvider {
            final File file;
            public FileStreamProvider(File file) {
                if (file == null) throw new IllegalStateException("File cannot be null");
                if (!file.exists()) throw new IllegalStateException("File must exist!");
                this.file = file;
            }
            public InputStream getInputStream() {
                try {
                    return new FileInputStream(file);
                } catch (FileNotFoundException e) {
                    Log.e(TAG, "FileStreamProvider.getStream() cannot open file. file: " + file, e);
                    return null;
                }
            }
            public boolean ready() {
                if (debug) Log.w(TAG, "ready() FileStreamProvider, file ready: " + file.getAbsolutePath());
                return true;
            }
        }

        private final class Decoder extends Thread {
            public Decoder(String threadName) {
                super(threadName);
            }

            public void run() {
                if (debug) Log.w(TAG, "ImageLoader.run() started");
                while (!shutdownLoader) {

                    ImageSpec spec = null;
                    // search bitmap ready to inflate
                    // wait for queue
                    synchronized (lock) {
                        while (spec == null && !shutdownLoader) {
                            try {
                                lock.wait();
                                if (shutdownLoader) return;
                                // picking from queue
                                for (int i = 0; i < queue.size(); i++) {
                                    if (queue.get(i).inputStreamProvider.ready()) { // ready to inflate
                                        spec = queue.remove(i);
                                        break;
                                    }
                                }
                            } catch (InterruptedException e) {
                            }
                        }
                    }

                    // decoding bitmap
                    int requiredWidth = spec.requiredWidth;
                    int requiredHeight = spec.requiredHeight;
                    // load
                    long started = System.currentTimeMillis();
                    InputStream streamForBounds = spec.inputStreamProvider.getInputStream();
                    if (streamForBounds == null) {
                        Log.e(TAG, "decodeImage() stream is null! Request cancelled. Spec: " + spec.id + ", provider: " + spec.inputStreamProvider.getClass().getSimpleName());
                        return;
                    }
                    BitmapFactory.Options originalOpts = new BitmapFactory.Options();
                    originalOpts.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(streamForBounds, null, originalOpts);
                    Tools.closeQuietly(streamForBounds);
                    // update spec if width and height are unknown
                    spec.originalWidth = originalOpts.outWidth;
                    spec.originalHeight = originalOpts.outHeight;
                    int sampleSize = 1;
                    while (originalOpts.outWidth / (sampleSize * 2) > requiredWidth) {
                        sampleSize *= 2;
                    }
                    BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
                    decodeOpts.inSampleSize = sampleSize;
                    Bitmap bmp = null;
                    InputStream streamForBitmap = spec.inputStreamProvider.getInputStream();
                    try {
                        bmp = BitmapFactory.decodeStream(streamForBitmap, null, decodeOpts);
                    } catch (OutOfMemoryError e) {
                        if (debug) Log.w(TAG, "decodeImage() out of memory. remove eldest");
                        removeEldest();
                        System.gc();
                    }
                    Tools.closeQuietly(streamForBitmap);
                    if (bmp != null) {
                        if (debug)
                            Log.d(TAG, "decodeImage() decoded " + bmp.getWidth() + "x" + bmp.getHeight()
                                    + " " + bmp.getByteCount() + " bytes"
                                    + " req: " + requiredWidth + "x" + requiredHeight
                                    + " original: " + originalOpts.outWidth + "x" + originalOpts.outHeight
                                    + " sampleSize: " + sampleSize
                                    + " in " + (System.currentTimeMillis() - started) + "ms from: " + spec.id);
                    } else {
                        if (debug)
                            Log.d(TAG, "decodeImage() not decoded " + " req: " + requiredWidth + "x" + requiredHeight
                                    + " in " + (System.currentTimeMillis() - started) + "ms from: " + spec.id);
                    }

                    // decoded
                    synchronized (lock) {
                        if (bmp != null) {
                            cache.put(spec.id, bmp);
                            if (spec.listener != null) spec.listener.onBitmapLoaded(spec);
                        } else if (spec.retries < BITMAP_DECODE_RETRIES) {
                            spec.retries++;
                            queue.add(0, spec);         // schedule retry
                            lock.notifyAll();
                        } /*else forget about this image, never put it back in queue */
                    }

                    if (debug)
                        Log.w(TAG, "decodeImage()   cache: " + cache.size() + ", queue: " + queue.size() + ", id: " + spec.id);
                }
            }
        }
    }
    
}
