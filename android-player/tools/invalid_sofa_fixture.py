#!/usr/bin/env python3
# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-3.0-only

"""Create a clean HDF5 that loads but fails mysofa_check (test fixture only)."""
import argparse
import netCDF4

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("source")
parser.add_argument("output")
args = parser.parse_args()
# A fresh file is important: HDF5 can retain superseded attribute messages
# after an in-place edit, which a third-party reader may still observe.
with netCDF4.Dataset(args.source) as source, netCDF4.Dataset(args.output, "w", format="NETCDF4") as target:
    for name, dimension in source.dimensions.items():
        target.createDimension(name, len(dimension))
    attrs = {key: source.getncattr(key) for key in source.ncattrs() if key != "_NCProperties"}
    attrs["SOFAConventions"] = "InvalidConvention"
    target.setncatts(attrs)
    for name, original in source.variables.items():
        extra = {"fill_value": original.getncattr("_FillValue")} if "_FillValue" in original.ncattrs() else {}
        copied = target.createVariable(name, original.dtype, original.dimensions, **extra)
        copied.setncatts({key: original.getncattr(key) for key in original.ncattrs() if key != "_FillValue"})
        copied[:] = original[:]
print("Created fresh invalid SOFA convention fixture")
