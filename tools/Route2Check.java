import com.phzt.railplanner.client.geom.*;
import java.util.*;
public class Route2Check {
    public static void main(String[] a) {
        int[][] pts = {{-33,64},{-1,83},{175,51},{191,-5},{150,-53}};
        List<Vec2> v = new ArrayList<>();
        for (int[] p : pts) v.add(new Vec2(p[0]+0.5, p[1]+0.5));
        Alignment al = Alignment.of(v, 24);
        BlockPath bp = Rasterizer.rasterize(al);
        System.out.println("geom=" + al.length() + " cells=" + bp.size() + " raster=" + bp.length);
        for (Alignment.Piece p : al.pieces()) {
            System.out.printf("%s start=%.1f len=%.1f%n", p.kind, p.startS, p.length);
        }
        // 对比：折线长度
        double poly = 0;
        for (int i=1;i<pts.length;i++) poly += Math.hypot(pts[i][0]-pts[i-1][0], pts[i][1]-pts[i-1][1]);
        System.out.println("polyline=" + poly);
    }
}
