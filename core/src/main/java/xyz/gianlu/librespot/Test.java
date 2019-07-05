package xyz.gianlu.librespot;

import com.spotify.connectstate.model.Connect;
import org.brotli.dec.BrotliInputStream;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * @author Gianlu
 */
public class Test {

    public static void main(String[] args) throws IOException {
        /*
        Connect.Cluster proto =  Connect.Cluster.parseFrom(new FileInputStream("C:\\Users\\Gianlu\\Downloads\\download.dat"));
        System.out.println(proto);
*/

        Connect.PutStateRequest proto = Connect.PutStateRequest.parseFrom(new BrotliInputStream(new FileInputStream("C:\\Users\\Gianlu\\Downloads\\req_new_2.dat")));
        System.out.println(proto);
    }
}
