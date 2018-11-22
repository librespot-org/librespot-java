package org.librespot.spotify;

import org.librespot.spotify.core.Session;
import org.librespot.spotify.mercury.MercuryClient;
import org.librespot.spotify.mercury.MercuryRequests;
import org.librespot.spotify.proto.Mercury;
import org.librespot.spotify.spirc.SpotifyIrc;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException, SpotifyIrc.IrcException, MercuryClient.PubSubException {
        Session session = new Session.Builder(Session.DeviceType.Computer, "librespot-java", new DefaultConfiguration())
                // .facebook()
                // .zeroconf()
                .userPass(args[0], args[1])
                // .blob("username", new byte[] {...})
                .create();

        String[] uris = new String[]{
                "hm://metadata/4/track/11e654cf9162497db13a8dd1f87cf9ff?country=IT&product=premium",
                "hm://metadata/4/track/622055fc90ff4353aced9ff193de0913?country=IT&product=premium",
                "hm://metadata/4/track/0696c73b153c4574bcd4ef93d7117eb9?country=IT&product=premium",
                "hm://metadata/4/track/087851db38d849c3b64ad1ccd1c33319?country=IT&product=premium",
                "hm://metadata/4/track/cd3a9080efdc4018aecad8051e9ca654?country=IT&product=premium",
                "hm://metadata/4/track/e7aa7b5aac384cb29c6926e343cddf67?country=IT&product=premium",
                "hm://metadata/4/track/88195a8040e841d2ab229aa9dcf4414c?country=IT&product=premium",
                "hm://metadata/4/track/44050a7ba0ea485ca2e963141cd17050?country=IT&product=premium",
                "hm://metadata/4/track/cd72d2450fac4ddfaaacbb2a0af15c56?country=IT&product=premium",
                "hm://metadata/4/track/73859f6694484743aabab577c2b56436?country=IT&product=premium",
                "hm://metadata/4/track/f75ec877b01e4c2599fdb497f40f072d?country=IT&product=premium",
                "hm://metadata/4/track/1897f36bf3fa46a08b6340ad216369c0?country=IT&product=premium",
                "hm://metadata/4/track/6d455f4f87d54320a0fdc112b3966c1a?country=IT&product=premium",
                "hm://metadata/4/track/e8af49e22a2a49c1821d7f4eaacaca67?country=IT&product=premium",
                "hm://metadata/4/track/1bd84353b3de415a93d9cbf64026f29c?country=IT&product=premium",
                "hm://metadata/4/track/976874d6bfe147a7b42b137a87aad1f3?country=IT&product=premium",
                "hm://metadata/4/track/e17bbfec801c479985d899de1a9ba91b?country=IT&product=premium",
                "hm://metadata/4/track/c705e6e2f1774b5da109da5ac9b3ca90?country=IT&product=premium",
                "hm://metadata/4/track/7147dbe2a3b646eb873e7f713690c5c2?country=IT&product=premium",
                "hm://metadata/4/track/66de6ed1e22f48d7bdc9d3080c7b60b1?country=IT&product=premium",
                "hm://metadata/4/track/2f4c0af777b145148caa4bb0f5f77fae?country=IT&product=premium",
                "hm://metadata/4/track/ee08b7b8fd8b4450ba366c15820aeda6?country=IT&product=premium",
                "hm://metadata/4/track/9e8d13a6a7104e8189faef41e767b18d?country=IT&product=premium",
                "hm://metadata/4/track/acbaae645a604998b3386ceec5e54085?country=IT&product=premium",
                "hm://metadata/4/track/e9e9935f0eae42b09402c9b30b33361c?country=IT&product=premium",
                "hm://metadata/4/track/542be9c5c3c74872a4b126d3ae388555?country=IT&product=premium",
                "hm://metadata/4/track/68c4a8f1e855415e8da94f000587ba59?country=IT&product=premium",
                "hm://metadata/4/track/5ee3a3f519e140bca04d44a08105fd3f?country=IT&product=premium",
                "hm://metadata/4/track/2ceacaaac589458fa521af86596a6a2c?country=IT&product=premium",
                "hm://metadata/4/track/0863231ef750451cacf7859c95ace4b4?country=IT&product=premium",
                "hm://metadata/4/track/44d1433a910442db9b700743eecde454?country=IT&product=premium",
                "hm://metadata/4/track/121afbf802b849fd9e2fb46d40fd4935?country=IT&product=premium",
                "hm://metadata/4/track/da255a8bacc0480a89e110cf5378e116?country=IT&product=premium",
                "hm://metadata/4/track/1ec713da3fc34a948d42f87af90c7d95?country=IT&product=premium",
                "hm://metadata/4/track/19d3fd8eb6814ef0aed58445c31125cd?country=IT&product=premium",
                "hm://metadata/4/track/47e1d384f31c4ff280e24c3ed6c035b7?country=IT&product=premium",
                "hm://metadata/4/track/8c7b7aceda604c7091e3cfb739a998f6?country=IT&product=premium",
                "hm://metadata/4/track/e4834c6e18fd4d9296b8061f51f0b795?country=IT&product=premium",
                "hm://metadata/4/track/59de7c4656e7469d9c8a8bc2ee137126?country=IT&product=premium",
                "hm://metadata/4/track/4a2313b7cd854836bba54dcbc9b4f16a?country=IT&product=premium",
                "hm://metadata/4/track/45967215b8b74168a17727520f6f7ebe?country=IT&product=premium",
                "hm://metadata/4/track/4a11c5257c23465284ef47591b7478fb?country=IT&product=premium",
                "hm://metadata/4/track/c00e8aae555649ccb6a3a46139760377?country=IT&product=premium",
                "hm://metadata/4/track/3ae47b72198a4d22bd669168cd3532b8?country=IT&product=premium",
                "hm://metadata/4/track/eb6ee974a1d74549962084b677825300?country=IT&product=premium",
                "hm://metadata/4/track/4b1e2bfae17548809edac5af81717804?country=IT&product=premium",
                "hm://metadata/4/track/94b6b50cf0be454cbe2c01345e9b878b?country=IT&product=premium",
                "hm://metadata/4/track/2dadbd0dcbc844318030c75031c397a7?country=IT&product=premium",
                "hm://metadata/4/track/fa1907e1f0d845f79db7aef7754860fb?country=IT&product=premium",
                "hm://metadata/4/track/f26568c04642451fa772e1e0da13b35e?country=IT&product=premium"};

        Mercury.MercuryRequest[] requests = new Mercury.MercuryRequest[uris.length];
        for (int i = 0; i < requests.length; i++)
            requests[i] = Mercury.MercuryRequest.newBuilder().setUri(uris[i]).build();

        try {
            Mercury.MercuryMultiGetReply reply = session.mercury().sendSync(
                    MercuryRequests.multiGet("hm://metadata/4/tracks?country=IT&product=premium", requests));

            System.out.println(reply);
        } catch (MercuryClient.MercuryException ex) {
            ex.printStackTrace();
        }

        ConsoleClient.start(session);
    }
}
