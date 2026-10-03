# Source provenance

The `itsadive` directory contains selected files from
[miccio-dk/itsadive_hybrid_structural_model](https://github.com/miccio-dk/itsadive_hybrid_structural_model),
pinned at `e43fbd5c50c1b15e6f1051b72d1f562ed736b8e8`.
The upstream MIT `LICENSE` and research README are preserved.

Runtime inputs: `vae_incept_edges.ckpt`, `dnn_edge_fullgrid.ckpt`,
`cvae_dense_fullgrid.ckpt`, the original `vae_incept.py` encoder definition,
and `matlab/data/subj_Z.sofa`. Their sizes and SHA-256 checksums are in
`itsadive/models.json`; the application checks them before inference.
Other MATLAB/Python files are retained for review and attribution.
Original MATLAB and PyTorch Lightning entry points are not our runtime.

The base SOFA is the pinna-less head/torso response from the
[Viking HRTF dataset v2](https://doi.org/10.5281/zenodo.4160401), as distributed
by the pinned ITSADIVE repository. It remains generic rather than a scan of
the current listener. The supplementary `pinnaless_VIKING.mat` has invalid
directions and is not used as a fallback.

Our inference adapter and Python acoustic stage are in `src/spatial_hrtf`.
Differences from the research pipeline are documented in the application
README and each generated report. In particular, two separately prepared
ears, minimum-phase FIR synthesis and analytical ITD are adaptations. The
current frequency-axis convention follows the upstream MATLAB implementation;
the precise sample rate used to train the checkpoint remains unconfirmed.
