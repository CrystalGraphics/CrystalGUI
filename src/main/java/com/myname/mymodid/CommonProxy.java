package com.myname.mymodid;

import org.appliedenergistics.yoga.YogaConstants;
import org.appliedenergistics.yoga.YogaNode;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());

        MyMod.LOG.info(Config.greeting);
        MyMod.LOG.info("I am MyMod at version " + Tags.VERSION);

        YogaNode root = new YogaNode();
        root.setWidth(100f);
        root.setHeight(100f);

        YogaNode child = new YogaNode();
        child.setFlexGrow(1f);
        root.addChildAt(child, 0);

        root.calculateLayout(YogaConstants.UNDEFINED, YogaConstants.UNDEFINED);

        System.out.println("Root: " + root.getLayoutWidth() + "x" + root.getLayoutHeight());
        System.out.println("Child: " + child.getLayoutWidth() + "x" + child.getLayoutHeight());
        System.out.println("Yoga is working!");
    }

    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent event) {}

    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {}

    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {}
}
