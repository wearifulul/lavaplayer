package com.sedmelluq.discord.lavaplayer.source.bandcamp;

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.FAULT;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

/**
 * Audio source manager that implements finding Bandcamp tracks based on URL.
 */
public class BandcampAudioSourceManager implements AudioSourceManager, HttpConfigurable {
  private static final String TRACK_URL_REGEX = "^https?://(?:[^.]+\\.|)bandcamp\\.com/track/([a-zA-Z0-9-_]+)/?(?:\\?.*|)$";
  private static final String ALBUM_URL_REGEX = "^https?://(?:[^.]+\\.|)bandcamp\\.com/album/([a-zA-Z0-9-_]+)/?(?:\\?.*|)$";
  private static final String BASE_URL_REGEX = "^(https?://(?:[^.]+\\.|)bandcamp\\.com)/(track|album)/([a-zA-Z0-9-_]+)/?(?:\\?.*|)$";
  private static final String ARTWORK_URL_FORMAT = "https://f4.bcbits.com/img/a%s_9.jpg";

  private static final Pattern trackUrlPattern = Pattern.compile(TRACK_URL_REGEX);
  private static final Pattern albumUrlPattern = Pattern.compile(ALBUM_URL_REGEX);
  private static final Pattern baseUrlRegex = Pattern.compile(BASE_URL_REGEX);

  private final HttpInterfaceManager httpInterfaceManager;

  /**
   * Create an instance.
   */
  public BandcampAudioSourceManager() {
    httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
  }

  @Override
  public String getSourceName() {
    return "bandcamp";
  }

  @Override
  public AudioItem loadItem(DefaultAudioPlayerManager manager, AudioReference reference) {
    if (trackUrlPattern.matcher(reference.identifier).matches()) {
      return loadTrack(reference.identifier);
    } else if (albumUrlPattern.matcher(reference.identifier).matches()) {
      return loadAlbum(reference.identifier);
    }
    return null;
  }

  private AudioItem loadTrack(String trackUrl) {
    return extractFromPage(trackUrl, (httpClient, text) -> {
      String bandUrl = readBandUrl(trackUrl);
      JsonBrowser trackListInfo = readTrackListInformation(text);
      String artist = trackListInfo.get("artist").safeText();
      String artwork = extractArtwork(trackListInfo);

      return extractTrack(trackListInfo.get("trackinfo").index(0), bandUrl, artist, artwork);
    });
  }

  private AudioItem loadAlbum(String albumUrl) {
    return extractFromPage(albumUrl, (httpClient, text) -> {
      String bandUrl = readBandUrl(albumUrl);
      JsonBrowser trackListInfo = readTrackListInformation(text);
      String artist = trackListInfo.get("artist").text();
      String artwork = extractArtwork(trackListInfo);

      List<AudioTrack> tracks = new ArrayList<>();
      for (JsonBrowser trackInfo : trackListInfo.get("trackinfo").values()) {
        tracks.add(extractTrack(trackInfo, bandUrl, artist, artwork));
      }

      JsonBrowser albumInfo = readAlbumInformation(text);
      return new BasicAudioPlaylist(albumInfo.get("current").get("title").text(), tracks, null, false);
    });
  }

  private AudioTrack extractTrack(JsonBrowser trackInfo, String bandUrl, String artist, String artwork) {
    String trackPageUrl = bandUrl + trackInfo.get("title_link").text();

    return new BandcampAudioTrack(new AudioTrackInfo(
        trackInfo.get("title").text(),
        artist,
        (long) (trackInfo.get("duration").as(Double.class) * 1000.0),
        bandUrl + trackInfo.get("title_link").text(),
        false,
        trackPageUrl,
        artwork
    ), this);
  }
  private String extractArtwork(JsonBrowser root) {
    String artId = root.get("art_id").text();
    if (artId != null) {
      if (artId.length() < 10) {
        StringBuilder builder = new StringBuilder(artId);
        while (builder.length() < 10) {
          builder.insert(0, "0");
        }
        artId = builder.toString();
      }
      return String.format(ARTWORK_URL_FORMAT, artId);
    }
    return null;
  }

  private String readBandUrl(String url) {
    String baseUrl = null;
    final Matcher matcher = baseUrlRegex.matcher(url);
    if (matcher.matches()) {
      baseUrl = matcher.group(1);
    }

    if (baseUrl == null) {
      throw new FriendlyException("Band information not found on the Bandcamp page.", SUSPICIOUS, null);
    }

    return baseUrl;
  }

  private JsonBrowser readAlbumInformation(String text) throws IOException {
    String albumInfoJson = DataFormatTools.extractBetween(text, "data-tralbum=\"", "\"");

    if (albumInfoJson == null) {
      throw new FriendlyException("Album information not found on the Bandcamp page.", SUSPICIOUS, null);
    }

    albumInfoJson = albumInfoJson.replace("&quot;", "\"");
    return JsonBrowser.parse(albumInfoJson);
  }

  JsonBrowser readTrackListInformation(String text) throws IOException {
    String trackInfoJson = DataFormatTools.extractBetween(text, "data-tralbum=\"", "\"");

    if (trackInfoJson == null) {
      throw new FriendlyException("Track information not found on the Bandcamp page.", SUSPICIOUS, null);
    }

    trackInfoJson = trackInfoJson.replace("&quot;", "\"");
    return JsonBrowser.parse(trackInfoJson);
  }

  private AudioItem extractFromPage(String url, AudioItemExtractor extractor) {
    try (HttpInterface httpInterface = httpInterfaceManager.getInterface()) {
      return extractFromPageWithInterface(httpInterface, url, extractor);
    } catch (Exception e) {
      throw ExceptionTools.wrapUnfriendlyExceptions("Loading information for a Bandcamp track failed.", FAULT, e);
    }
  }

  private AudioItem extractFromPageWithInterface(HttpInterface httpInterface, String url, AudioItemExtractor extractor) throws Exception {
    String responseText;

    try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(url))) {
      int statusCode = response.getStatusLine().getStatusCode();

      if (statusCode == HttpStatus.SC_NOT_FOUND) {
        return new AudioReference(null, null);
      } else if (!HttpClientTools.isSuccessWithContent(statusCode)) {
        throw new IOException("Invalid status code for track page: " + statusCode);
      }

      responseText = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
    }

    return extractor.extract(httpInterface, responseText);
  }

  @Override
  public boolean isTrackEncodable(AudioTrack track) {
    return true;
  }

  @Override
  public void encodeTrack(AudioTrack track, DataOutput output) throws IOException {
    // No special values to encode
  }

  @Override
  public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
    return new BandcampAudioTrack(trackInfo, this);
  }

  @Override
  public void shutdown() {
    ExceptionTools.closeWithWarnings(httpInterfaceManager);
  }

  /**
   * @return Get an HTTP interface for a playing track.
   */
  public HttpInterface getHttpInterface() {
    return httpInterfaceManager.getInterface();
  }

  @Override
  public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
    httpInterfaceManager.configureRequests(configurator);
  }

  @Override
  public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
    httpInterfaceManager.configureBuilder(configurator);
  }

  private interface AudioItemExtractor {
    AudioItem extract(HttpInterface httpInterface, String text) throws Exception;
  }
}