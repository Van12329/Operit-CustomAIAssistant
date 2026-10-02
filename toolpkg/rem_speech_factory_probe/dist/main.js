"use strict";

function registerToolPkg() {
    // Intentionally no lifecycle hook. N01 runs only when its explicit tool is called.
    return true;
}

exports.registerToolPkg = registerToolPkg;
