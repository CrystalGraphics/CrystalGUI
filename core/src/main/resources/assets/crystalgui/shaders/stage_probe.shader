// The mark StageProbe draws on CrystalGUI's stages in an unattended run: a flat quad in its colour, the least a mod
// draws at a stage with.

#type none
#pragma cg_use quad

Tags { "RenderType" = "Transparent" }
Queue = "Overlay"

struct v2f {
    vec4 color;
};

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        gl_Position = cg_ProjMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
        o.color = CG_QUAD_COLOR;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = i.color;
    }
}
