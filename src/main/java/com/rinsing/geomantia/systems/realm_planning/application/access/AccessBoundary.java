package com.rinsing.geomantia.systems.realm_planning.application.access;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/** Local contour of the movement authority. Never reads terrain or requests chunks. */
public final class AccessBoundary {
    public static final int RADIUS = 96;
    private static final int STEP = 8;
    public record Segment(double x1, double z1, double x2, double z2) {}
    private record Point(double x, double z) {}

    public static List<Segment> sample(double centerX, double centerZ, BiPredicate<Double, Double> allowed) {
        int minX = Math.floorDiv((int)Math.floor(centerX), 16) * 16 - RADIUS;
        int minZ = Math.floorDiv((int)Math.floor(centerZ), 16) * 16 - RADIUS;
        int n = RADIUS * 2 / STEP;
        boolean[][] grid = new boolean[n + 1][n + 1];
        for (int x = 0; x <= n; x++) for (int z = 0; z <= n; z++)
            grid[x][z] = allowed.test((double)minX + x * STEP, (double)minZ + z * STEP);
        List<Segment> result = new ArrayList<>();
        for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) {
            Point[] p = {new Point(minX+x*STEP,minZ+z*STEP), new Point(minX+(x+1)*STEP,minZ+z*STEP),
                    new Point(minX+(x+1)*STEP,minZ+(z+1)*STEP),new Point(minX+x*STEP,minZ+(z+1)*STEP)};
            boolean[] a = {grid[x][z],grid[x+1][z],grid[x+1][z+1],grid[x][z+1]};
            List<Point> crossings = new ArrayList<>(4);
            for (int i = 0; i < 4; i++) if (a[i] != a[(i+1)%4]) {
                Point lo=p[i], hi=p[(i+1)%4];
                for (int j=0;j<7;j++) {
                    Point mid=new Point((lo.x+hi.x)/2,(lo.z+hi.z)/2);
                    if (allowed.test(mid.x,mid.z)==a[i]) lo=mid; else hi=mid;
                }
                crossings.add(new Point((lo.x+hi.x)/2,(lo.z+hi.z)/2));
            }
            for (int i=0;i+1<crossings.size();i+=2) {
                Point v=crossings.get(i),w=crossings.get(i+1);
                result.add(new Segment(v.x,v.z,w.x,w.z));
            }
        }
        return List.copyOf(result);
    }
    public static double distance(Segment s, double x, double z) {
        double dx=s.x2-s.x1,dz=s.z2-s.z1,length=dx*dx+dz*dz;
        double t=length==0?0:Math.max(0,Math.min(1,((x-s.x1)*dx+(z-s.z1)*dz)/length));
        return Math.hypot(x-s.x1-t*dx,z-s.z1-t*dz);
    }
}
