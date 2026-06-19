package cz.luigismp.screen;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

record ViewerPosition(Player player, World world, double x, double y, double z) {

    static ViewerPosition capture(Player player) {
        Location location = player.getLocation();
        return new ViewerPosition(
                player,
                location.getWorld(),
                location.getX(),
                location.getY(),
                location.getZ());
    }
}
