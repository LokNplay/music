package com.zionhuang.music.playback

import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR
import android.util.Pair
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.PlaybackException.ERROR_CODE_REMOTE_ERROR
import com.google.android.exoplayer2.Player.*
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.analytics.PlaybackStats
import com.google.android.exoplayer2.analytics.PlaybackStatsListener
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueEditor.*
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.ResolvingDataSource
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.*
import com.zionhuang.innertube.models.QueueAddEndpoint.Companion.INSERT_AFTER_CURRENT_VIDEO
import com.zionhuang.innertube.models.QueueAddEndpoint.Companion.INSERT_AT_END
import com.zionhuang.music.R
import com.zionhuang.music.constants.MediaConstants.EXTRA_MEDIA_METADATA_ITEMS
import com.zionhuang.music.constants.MediaConstants.STATE_DOWNLOADED
import com.zionhuang.music.constants.MediaSessionConstants.ACTION_ADD_TO_LIBRARY
import com.zionhuang.music.constants.MediaSessionConstants.COMMAND_ADD_TO_QUEUE
import com.zionhuang.music.constants.MediaSessionConstants.COMMAND_PLAY_NEXT
import com.zionhuang.music.constants.MediaSessionConstants.COMMAND_SEEK_TO_QUEUE_ITEM
import com.zionhuang.music.constants.MediaSessionConstants.EXTRA_MEDIA_ID
import com.zionhuang.music.extensions.*
import com.zionhuang.music.models.MediaMetadata
import com.zionhuang.music.playback.queues.EmptyQueue
import com.zionhuang.music.playback.queues.Queue
import com.zionhuang.music.repos.SongRepository
import com.zionhuang.music.ui.activities.MainActivity
import com.zionhuang.music.ui.bindings.resizeThumbnailUrl
import com.zionhuang.music.utils.preference.enumPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A wrapper around [ExoPlayer]
 */
class SongPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    notificationListener: PlayerNotificationManager.NotificationListener,
) : Listener, PlaybackStatsListener.Callback {
    private val localRepository = SongRepository
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val bitmapProvider = BitmapProvider(context)
    private var autoAddSong by context.preference(R.string.pref_auto_add_song, true)
    private var audioQuality by enumPreference(context, R.string.pref_audio_quality, AudioQuality.AUTO)
    private var currentQueue: Queue = EmptyQueue()

    val mediaSession = MediaSessionCompat(context, context.getString(R.string.app_name)).apply {
        isActive = true
    }

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(ResolvingDataSource.Factory(
                DefaultDataSource.Factory(context)
            ) { dataSpec ->
                val mediaId = dataSpec.key ?: error("No media id")
                val song = runBlocking(IO) { localRepository.getSongById(mediaId) }
                if (song?.song?.downloadState == STATE_DOWNLOADED) {
                    return@Factory dataSpec.withUri(localRepository.getSongFile(mediaId).toUri())
                }
                runBlocking(IO) {
                    YouTube.player(mediaId)
                }.mapCatching { playerResponse ->
                    if (playerResponse.playabilityStatus.status != "OK") {
                        throw PlaybackException(playerResponse.playabilityStatus.reason, null, ERROR_CODE_REMOTE_ERROR)
                    }
                    val uri = playerResponse.streamingData?.adaptiveFormats
                        ?.filter { it.isAudio }
                        ?.maxByOrNull {
                            it.bitrate * when (audioQuality) {
                                AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                                AudioQuality.HIGH -> 1
                                AudioQuality.LOW -> -1
                            }
                        }
                        ?.url?.toUri()
                        ?: throw PlaybackException("No stream available", null, ERROR_CODE_NO_STREAM)
                    dataSpec.withUri(uri)
                }.getOrElse { throwable ->
                    throw PlaybackException("Unknown error", throwable, ERROR_CODE_REMOTE_ERROR)
                }
            })
        )
        .build()
        .apply {
            addListener(this@SongPlayer)
            addAnalyticsListener(PlaybackStatsListener(false, this@SongPlayer))
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            setAudioAttributes(audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
        }

    private val mediaSessionConnector = MediaSessionConnector(mediaSession).apply {
        setPlayer(player)
        setPlaybackPreparer(object : MediaSessionConnector.PlaybackPreparer {
            override fun onCommand(player: Player, command: String, extras: Bundle?, cb: ResultReceiver?) = false
            override fun getSupportedPrepareActions(): Long = 0L
            override fun onPrepareFromMediaId(mediaId: String, playWhenReady: Boolean, extras: Bundle?) {}
            override fun onPrepareFromSearch(query: String, playWhenReady: Boolean, extras: Bundle?) {}
            override fun onPrepareFromUri(uri: Uri, playWhenReady: Boolean, extras: Bundle?) {}
            override fun onPrepare(playWhenReady: Boolean) {
                player.playWhenReady = playWhenReady
                player.prepare()
            }
        })
        registerCustomCommandReceiver { player, command, extras, _ ->
            if (extras == null) return@registerCustomCommandReceiver false
            when (command) {
                COMMAND_MOVE_QUEUE_ITEM -> {
                    val from = extras.getInt(EXTRA_FROM_INDEX, C.INDEX_UNSET)
                    val to = extras.getInt(EXTRA_TO_INDEX, C.INDEX_UNSET)
                    if (from != C.INDEX_UNSET && to != C.INDEX_UNSET) {
                        player.moveMediaItem(from, to)
                    }
                    true
                }
                COMMAND_SEEK_TO_QUEUE_ITEM -> {
                    val mediaId = extras.getString(EXTRA_MEDIA_ID)
                        ?: return@registerCustomCommandReceiver true
                    player.mediaItemIndexOf(mediaId)?.let {
                        player.seekToDefaultPosition(it)
                    }
                    true
                }
                COMMAND_PLAY_NEXT -> {
                    player.addMediaItems(
                        if (player.mediaItemCount == 0) 0 else player.currentMediaItemIndex + 1,
                        extras.getParcelableArray(EXTRA_MEDIA_METADATA_ITEMS)!!.filterIsInstance<MediaMetadata>().map { it.toMediaItem() }
                    )
                    player.prepare()
                    true
                }
                COMMAND_ADD_TO_QUEUE -> {
                    player.addMediaItems(extras.getParcelableArray(EXTRA_MEDIA_METADATA_ITEMS)!!.filterIsInstance<MediaMetadata>().map { it.toMediaItem() })
                    player.prepare()
                    true
                }
                else -> false
            }
        }
        setCustomActionProviders(context.createCustomAction(ACTION_ADD_TO_LIBRARY, R.string.custom_action_add_to_library, R.drawable.ic_library_add) { _, _, _ ->
            player.currentMetadata?.let {
                addToLibrary(it)
            }
        })
        setQueueNavigator { player, windowIndex -> player.getMediaItemAt(windowIndex).metadata!!.toMediaDescription() }
        setErrorMessageProvider { e -> Pair(ERROR_CODE_UNKNOWN_ERROR, e.localizedMessage) }
        setQueueEditor(object : MediaSessionConnector.QueueEditor {
            override fun onCommand(player: Player, command: String, extras: Bundle?, cb: ResultReceiver?) = false
            override fun onAddQueueItem(player: Player, description: MediaDescriptionCompat) = throw UnsupportedOperationException()
            override fun onAddQueueItem(player: Player, description: MediaDescriptionCompat, index: Int) = throw UnsupportedOperationException()
            override fun onRemoveQueueItem(player: Player, description: MediaDescriptionCompat) {
                player.mediaItemIndexOf(description.mediaId)?.let { i ->
                    player.removeMediaItem(i)
                }
            }
        })
    }

    private val playerNotificationManager = PlayerNotificationManager.Builder(context, NOTIFICATION_ID, CHANNEL_ID)
        .setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {
            override fun getCurrentContentTitle(player: Player): CharSequence =
                player.currentMetadata?.title.orEmpty()

            override fun getCurrentContentText(player: Player): CharSequence? =
                player.currentMetadata?.artists?.joinToString { it.name }

            override fun getCurrentLargeIcon(player: Player, callback: PlayerNotificationManager.BitmapCallback): Bitmap? =
                player.currentMetadata?.thumbnailUrl?.let { url ->
                    bitmapProvider.load(resizeThumbnailUrl(url, (256 * context.resources.displayMetrics.density).roundToInt(), null)) {
                        callback.onBitmap(it)
                    }
                }

            override fun createCurrentContentIntent(player: Player): PendingIntent? =
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), FLAG_IMMUTABLE)
        })
        .setChannelNameResourceId(R.string.channel_name_playback)
        .setNotificationListener(notificationListener)
        .build()
        .apply {
            setPlayer(player)
            setMediaSessionToken(mediaSession.sessionToken)
            setSmallIcon(R.drawable.ic_notification)
        }


    fun playQueue(queue: Queue) {
        currentQueue = queue
        player.clearMediaItems()

        scope.launch(context.exceptionHandler) {
            val initialStatus = withContext(IO) { queue.getInitialStatus() }
            player.setMediaItems(initialStatus.items)
            if (initialStatus.index > 0) player.seekToDefaultPosition(initialStatus.index)
            player.prepare()
            player.playWhenReady = true
        }
    }

    fun handleQueueAddEndpoint(endpoint: QueueAddEndpoint, item: YTItem?) {
        scope.launch(context.exceptionHandler) {
            val items = when (item) {
                is SongItem -> YouTube.getQueue(videoIds = listOf(item.id)).getOrThrow().map { it.toMediaItem() }
                is AlbumItem -> withContext(IO) {
                    YouTube.browse(BrowseEndpoint(browseId = "VL" + item.playlistId)).getOrThrow().items.filterIsInstance<SongItem>().map { it.toMediaItem() }
                    // consider refetch by [YouTube.getQueue] if needed
                }
                is PlaylistItem -> withContext(IO) {
                    YouTube.getQueue(playlistId = endpoint.queueTarget.playlistId!!).getOrThrow().map { it.toMediaItem() }
                }
                is ArtistItem -> return@launch
                null -> when {
                    endpoint.queueTarget.videoId != null -> withContext(IO) {
                        YouTube.getQueue(videoIds = listOf(endpoint.queueTarget.videoId!!)).getOrThrow().map { it.toMediaItem() }
                    }
                    endpoint.queueTarget.playlistId != null -> withContext(IO) {
                        YouTube.getQueue(playlistId = endpoint.queueTarget.playlistId).getOrThrow().map { it.toMediaItem() }
                    }
                    else -> error("Unknown queue target")
                }
            }
            when (endpoint.queueInsertPosition) {
                INSERT_AFTER_CURRENT_VIDEO -> player.addMediaItems((if (player.mediaItemCount == 0) -1 else player.currentMediaItemIndex) + 1, items)
                INSERT_AT_END -> player.addMediaItems(items)
                else -> {}
            }
            player.prepare()
        }
    }

    fun playNext(items: List<MediaItem>) {
        player.addMediaItems(if (player.mediaItemCount == 0) 0 else player.currentMediaItemIndex + 1, items)
        player.prepare()
    }

    fun addToQueue(items: List<MediaItem>) {
        player.addMediaItems(items)
        player.prepare()
    }

    private fun openAudioEffectSession() {
        context.sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
        )
    }

    private fun closeAudioEffectSession() {
        context.sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
            }
        )
    }

    /**
     * Auto load more
     */
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == MEDIA_ITEM_TRANSITION_REASON_REPEAT ||
            player.playbackState == STATE_IDLE ||
            player.mediaItemCount - player.currentMediaItemIndex > 5 ||
            !currentQueue.hasNextPage()
        ) return
        scope.launch(context.exceptionHandler) {
            player.addMediaItems(currentQueue.nextPage())
        }
    }

    override fun onPositionDiscontinuity(oldPosition: PositionInfo, newPosition: PositionInfo, @DiscontinuityReason reason: Int) {
        if (reason == DISCONTINUITY_REASON_AUTO_TRANSITION && autoAddSong) {
            oldPosition.mediaItem?.metadata?.let {
                addToLibrary(it)
            }
        }
    }

    override fun onPlaybackStateChanged(@State playbackState: Int) {
        if (playbackState == STATE_ENDED && autoAddSong) {
            player.currentMetadata?.let {
                addToLibrary(it)
            }
        }
    }

    override fun onEvents(player: Player, events: Events) {
        if (events.containsAny(EVENT_PLAYBACK_STATE_CHANGED, EVENT_PLAY_WHEN_READY_CHANGED, EVENT_IS_PLAYING_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            if (player.playbackState != STATE_ENDED && player.playWhenReady) {
                openAudioEffectSession()
            } else {
                closeAudioEffectSession()
            }
        }
    }

    override fun onPlaybackStatsReady(eventTime: AnalyticsListener.EventTime, playbackStats: PlaybackStats) {
        val mediaItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
        scope.launch {
            SongRepository.incrementSongTotalPlayTime(mediaItem.mediaId, playbackStats.totalPlayTimeMs)
        }
    }

    private fun addToLibrary(mediaMetadata: MediaMetadata) {
        scope.launch(context.exceptionHandler) {
            localRepository.addSong(mediaMetadata)
        }
    }

    fun release() {
        mediaSession.apply {
            isActive = false
            release()
        }
        mediaSessionConnector.setPlayer(null)
        playerNotificationManager.setPlayer(null)
        player.removeListener(this)
        player.release()
    }

    enum class AudioQuality {
        AUTO, HIGH, LOW
    }

    companion object {
        const val CHANNEL_ID = "music_channel_01"
        const val NOTIFICATION_ID = 888

        const val ERROR_CODE_NO_STREAM = 1000001
    }
}