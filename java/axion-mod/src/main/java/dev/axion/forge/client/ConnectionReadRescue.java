package dev.axion.forge.client;

import com.mojang.logging.LogUtils;
import dev.axion.AxionMod;
import dev.axion.forge.RuntimeAccess;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.net.ReadRescue;
import io.netty.channel.Channel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.channels.SelectionKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.network.Connection;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Le rattrapage de la course de connexion de Forge 47 chez le joueur (ADR-169) : tant que l'écran de connexion est
 * ouvert, son canal se surveille à chaque tick du client, et une lecture restée désinscrite se redemande
 * ({@link ReadRescue}). Sans Mixin ni nom de champ : le champ de type {@link Connection} de l'écran, la clé de sélection
 * de Netty par réflexion. Un autre mod qui corrige ou rattrape la même course n'est pas gêné : sa lecture inscrite fait
 * taire celui-ci, et une lecture redemandée deux fois ne change rien. Coupé par {@code net.connection_read_rescue}.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ConnectionReadRescue {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ReadRescue RESCUE = new ReadRescue();
    private static Field connectionField;
    private static Method selectionKey;
    private static boolean reflectionFailed;

    private ConnectionReadRescue() {}

    /** Chaque fin de tick du client. Son nom est à lui : EventBus 6 nomme ses gestionnaires d'après la méthode. */
    @SubscribeEvent
    public static void watchConnectScreen(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof ConnectScreen screen) || !enabled()) {
            if (RESCUE.rescues() > 0) {
                LOGGER.info("AXION : connexion : lecture du client relancée {} fois (course de connexion de Forge 47,"
                        + " ADR-169)", RESCUE.rescues());
            }
            RESCUE.reset();
            return;
        }
        Connection connection = connectionOf(screen);
        Channel channel = connection == null ? null : connection.channel();
        if (channel == null) {
            return;
        }
        if (RESCUE.observe(System.nanoTime(), channel.isActive(), channel.config().isAutoRead(), readOf(channel))) {
            if (RESCUE.rescues() == 1) {
                LOGGER.info("AXION : connexion : la lecture du client était coupée depuis une seconde (course de"
                        + " connexion de Forge 47) — relancée");
            }
            channel.read();
        }
    }

    private static boolean enabled() {
        AxionRuntime runtime = RuntimeAccess.get();
        // Sans configuration lue, le défaut : le rattrapage.
        return runtime == null || runtime.outcome() == null
                || runtime.outcome().config().getBoolean("net.connection_read_rescue");
    }

    /** {@return la connexion de l'écran, par le seul champ de son type ; {@code null} si elle n'existe pas encore} */
    private static Connection connectionOf(ConnectScreen screen) {
        if (reflectionFailed) {
            return null;
        }
        try {
            if (connectionField == null) {
                for (Field field : ConnectScreen.class.getDeclaredFields()) {
                    if (field.getType() == Connection.class) {
                        field.setAccessible(true);
                        connectionField = field;
                        break;
                    }
                }
                if (connectionField == null) {
                    throw new NoSuchFieldException("aucun champ de type Connection dans ConnectScreen");
                }
            }
            return (Connection) connectionField.get(screen);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            reflectionFailed = true;
            LOGGER.warn("AXION : connexion : rattrapage de la course de connexion indisponible — {}", failure.toString());
            return null;
        }
    }

    /** {@return l'abonnement du canal à la lecture, s'il se lit : sur une clé NIO seulement} */
    private static ReadRescue.Read readOf(Channel channel) {
        if (!(channel instanceof io.netty.channel.nio.AbstractNioChannel)) {
            return ReadRescue.Read.UNKNOWN;
        }
        try {
            if (selectionKey == null) {
                selectionKey = io.netty.channel.nio.AbstractNioChannel.class.getDeclaredMethod("selectionKey");
                selectionKey.setAccessible(true);
            }
            SelectionKey key = (SelectionKey) selectionKey.invoke(channel);
            if (key == null || !key.isValid()) {
                return ReadRescue.Read.UNKNOWN;
            }
            return (key.interestOps() & SelectionKey.OP_READ) != 0 ? ReadRescue.Read.REGISTERED : ReadRescue.Read.MISSING;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return ReadRescue.Read.UNKNOWN;
        }
    }
}
