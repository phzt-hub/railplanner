import com.phzt.railplanner.client.geom.*;
import java.util.*;
public class RouteCheck {
    public static void main(String[] a) {
        List<Vec2> pts = List.of(new Vec2(13.5,-20.5), new Vec2(67.5,-2.5), new Vec2(107.5,64.5), new Vec2(7.5,90.5));
        Alignment al = Alignment.of(pts, 24);
        BlockPath p = Rasterizer.rasterize(al);
        System.out.println("geom length = " + al.length());
        System.out.println("corners = " + al.corners().size());
        System.out.println("cells = " + p.size());
        System.out.println("raster length = " + p.length);
        for (Alignment.Corner c : al.corners()) {
            System.out.printf("corner %d R=%.2f defl=%.2f T=%.2f L=%.2f E=%.2f%n", c.anchorIndex, c.radius, c.deflectionDegrees(), c.tangent, c.arcLength, c.external);
        }
    }
}
