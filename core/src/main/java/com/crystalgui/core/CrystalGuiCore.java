package com.crystalgui.core;


import com.crystalgui.core.input.CgUiInputAdapter;
import lombok.Getter;
import lombok.Setter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CrystalGuiCore {

    public static final Logger LOGGER = LogManager.getLogger("CrystalGui");

    @Getter @Setter
    private static CgUiInputAdapter adapter;


}
