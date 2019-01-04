package xyz.gianlu.librespot.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.RawMercuryRequest;
import xyz.gianlu.librespot.mercury.model.TrackId;

import java.io.IOException;
import java.io.InputStreamReader;

/**
 * @author Gianlu
 */
public class StationProvider implements TracksProvider {
    private static final Logger LOGGER = Logger.getLogger(StationProvider.class);
    private final MercuryClient mercury;
    private final Spirc.State.Builder state;
    private String nextPageUri;

    public StationProvider(@NotNull Session session, @NotNull Spirc.State.Builder state, @NotNull Spirc.Frame frame) {
        this.mercury = session.mercury();
        this.state = state;

        state.setPlayingTrackIndex(frame.getState().getPlayingTrackIndex());
        state.clearTrack();
        state.addAllTrack(frame.getState().getTrackList());
    }

    @Override
    public int getNextTrackIndex(boolean consume) {
        int next = state.getPlayingTrackIndex() + 1;
        if (next >= state.getTrackCount()) {
            try {
                requestMore();
            } catch (IOException | MercuryClient.MercuryException ex) {
                LOGGER.fatal("Failed requesting more tracks!", ex);
                return state.getPlayingTrackIndex();
            }
        }

        return next;
    }

    private void requestMore() throws IOException, MercuryClient.MercuryException {
        if (nextPageUri == null)
            resolveContext();

        getNextPage();
    }

    private void getNextPage() throws IOException {
        MercuryClient.Response resp = mercury.sendSync(RawMercuryRequest.newBuilder()
                .setUri(nextPageUri)
                .setMethod("GET")
                .build());

        JsonObject obj = new JsonParser().parse(new InputStreamReader(resp.payload.stream())).getAsJsonObject();
        nextPageUri = obj.get("next_page_url").getAsString();
        LOGGER.trace("Next page URI: " + nextPageUri);

        JsonArray tracks = obj.getAsJsonArray("tracks");
        for (JsonElement elm : tracks) {
            JsonObject track = elm.getAsJsonObject();
            state.addTrack(Spirc.TrackRef.newBuilder()
                    .setGid(ByteString.copyFrom(TrackId.fromUri(track.get("uri").getAsString()).getGid()))
                    .build());
        }
    }

    private void resolveContext() throws IOException, MercuryClient.MercuryException {
        if (!state.hasContextUri()) {
            LOGGER.fatal("Missing context URI!");
            return;
        }

        MercuryRequests.ResolvedContextWrapper json = mercury.sendSync(MercuryRequests.resolveContext(state.getContextUri()));
        JsonObject firstPage = json.pages().get(0).getAsJsonObject();
        nextPageUri = firstPage.get("next_page_url").getAsString();
        LOGGER.trace("Next page URI: " + nextPageUri);
    }

    @Override
    public int getPrevTrackIndex(boolean consume) {
        int prev = state.getPlayingTrackIndex() - 1;
        if (prev < 0) return state.getPlayingTrackIndex();
        else return prev;
    }

    @Override
    public @NotNull TrackId getCurrentTrack() {
        return TrackId.fromTrackRef(state.getTrack(state.getPlayingTrackIndex()));
    }

    @Override
    public @NotNull TrackId getTrackAt(int index) {
        return TrackId.fromTrackRef(state.getTrack(index));
    }

    @Override
    public boolean canShuffle() {
        return false;
    }

    @Override
    public boolean canRepeat() {
        return false;
    }
}
