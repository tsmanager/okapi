package org.folio.okapi.service;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import org.folio.okapi.bean.ModuleDescriptor;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.logging.Logger;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.folio.okapi.bean.InterfaceDescriptor;
import org.folio.okapi.bean.Tenant;
import org.folio.okapi.bean.TenantModuleDescriptor;
import static org.folio.okapi.common.ErrorType.*;
import org.folio.okapi.common.ExtendedAsyncResult;
import org.folio.okapi.common.Failure;
import org.folio.okapi.common.OkapiLogger;
import org.folio.okapi.common.Success;
import org.folio.okapi.util.CompList;
import org.folio.okapi.util.LockedTypedMap1;
import org.folio.okapi.common.ModuleId;

/**
 * Manages a list of modules known to Okapi's "/_/proxy". Maintains consistency
 * checks on module versions, etc. Stores them in the database too, if we have
 * one.
 */
public class ModuleManager {

  private final Logger logger = OkapiLogger.get();
  private TenantManager tenantManager = null;
  private String mapName = "modules";
  private LockedTypedMap1<ModuleDescriptor> modules
    = new LockedTypedMap1<>(ModuleDescriptor.class);
  private ModuleStore moduleStore;

  public ModuleManager(ModuleStore moduleStore) {
    this.moduleStore = moduleStore;
  }

  /**
   * Force the map to be local. Even in cluster mode, will use a local memory
   * map. This way, the node will not share tenants with the cluster, and can
   * not proxy requests for anyone but the superTenant, to the InternalModule.
   * Which is just enough to act in the deployment mode.
   */
  public void forceLocalMap() {
    mapName = null;
  }

  public void setTenantManager(TenantManager tenantManager) {
    this.tenantManager = tenantManager;
  }

  public void init(Vertx vertx, Handler<ExtendedAsyncResult<Void>> fut) {
    modules.init(vertx, mapName, ires -> {
      if (ires.failed()) {
        fut.handle(new Failure<>(ires.getType(), ires.cause()));
      } else {
        loadModules(fut);
      }
    });
  }

  /**
   * Load the modules from the database, if not already loaded.
   *
   * @param fut
   */
  private void loadModules(Handler<ExtendedAsyncResult<Void>> fut) {
    if (moduleStore == null) {
      fut.handle(new Success<>());
    } else {
      modules.size(kres -> {
        if (kres.failed()) {
          fut.handle(new Failure<>(INTERNAL, kres.cause()));
        } else if (kres.result() > 0) {
          logger.debug("Not loading modules, looks like someone already did");
          fut.handle(new Success<>());
        } else {
          moduleStore.getAll(mres -> {
            if (mres.failed()) {
              fut.handle(new Failure<>(mres.getType(), mres.cause()));
            } else {
              CompList<Void> futures = new CompList<>(INTERNAL);
              for (ModuleDescriptor md : mres.result()) {
                Future<Void> f = Future.future();
                modules.add(md.getId(), md, f::handle);
                futures.add(f);
              }
              futures.all(fut);
            }
          });
        }
      });
    }
  }

  /**
   * Check one dependency.
   *
   * @param md module to check
   * @param req required dependency
   * @param modlist the list to check against
   * @return "" if ok, or error message
   */
  private String checkOneDependency(ModuleDescriptor md, InterfaceDescriptor req,
    Map<String, ModuleDescriptor> modlist) {
    InterfaceDescriptor seenversion = null;
    for (Map.Entry<String, ModuleDescriptor> entry : modlist.entrySet()) {
      ModuleDescriptor rm = entry.getValue();
      for (InterfaceDescriptor pi : rm.getProvidesList()) {
        logger.debug("Checking dependency of " + md.getId() + ": "
          + req.getId() + " " + req.getVersion()
          + " against " + pi.getId() + " " + pi.getVersion());
        if (req.getId().equals(pi.getId())) {
          seenversion = pi;
          if (pi.isCompatible(req)) {
            logger.debug("Dependency OK");
            return "";  // ok
          }
        }
      }
    }
    if (seenversion == null) {
      return "Missing dependency: " + md.getId()
              + " requires " + req.getId() + ": " + req.getVersion();
    } else {
      return "Incompatible version for module " + md.getId() + " interface " + req.getId() + ". "
              + "Need " + req.getVersion() + ". have " + seenversion.getVersion();
    }
  }

  /**
   * Check that the dependencies are satisfied.
   *
   * @param md Module to be checked
   * @return "" if no problems, or an error message
   *
   * This could be done like we do conflicts, by building a map and checking
   * against that...
   */
  private String checkDependencies(ModuleDescriptor md,
    Map<String, ModuleDescriptor> modlist) {
    logger.debug("Checking dependencies of " + md.getId());
    for (InterfaceDescriptor req : md.getRequiresList()) {
      String res = checkOneDependency(md, req, modlist);
      if (!res.isEmpty()) {
        return res;
      }
    }
    return "";  // ok
  }

  private int checkInterfaceDependency(InterfaceDescriptor req,
    Map<String, ModuleDescriptor> modsAvailable, Map<String, ModuleDescriptor> modsEnabled,
    List<TenantModuleDescriptor> tml) {
    logger.info("checkInterfaceDependency1");
    for (Map.Entry<String, ModuleDescriptor> entry : modsEnabled.entrySet()) {
      ModuleDescriptor md = entry.getValue();
      for (InterfaceDescriptor pi : md.getProvidesList()) {
        if (req.getId().equals(pi.getId()) && pi.isCompatible(req)) {
          logger.debug("Dependency OK");
          return 0;
        }
      }
    }
    logger.info("checkInterfaceDependency2");
    ModuleDescriptor foundMd = null;
    for (Map.Entry<String, ModuleDescriptor> entry : modsAvailable.entrySet()) {
      ModuleDescriptor md = entry.getValue();
      for (InterfaceDescriptor pi : md.getProvidesList()) {
        if (req.getId().equals(pi.getId()) && pi.isCompatible(req)
          && (foundMd == null || md.compareTo(foundMd) > 0)) {// newest module
          foundMd = md;
        }
      }
    }
    if (foundMd == null) {
      return -1;
    }
    return addModuleDependencies(foundMd, modsAvailable, modsEnabled, tml);
  }

  private int resolveModuleConflicts(ModuleDescriptor md, Map<String, ModuleDescriptor> modsEnabled,
    List<TenantModuleDescriptor> tml, List<ModuleDescriptor> fromModule) {
    int v = 0;
    Iterator<String> it = modsEnabled.keySet().iterator();
    while (it.hasNext()) {
      String runningmodule = it.next();
      ModuleDescriptor rm = modsEnabled.get(runningmodule);
      if (md.getProduct().equals(rm.getProduct())) {
        logger.info("resolveModuleConflicts from " + runningmodule);
        fromModule.add(rm);
        modsEnabled.remove(runningmodule);
        it = modsEnabled.keySet().iterator();
        v++;
      } else {
        for (InterfaceDescriptor pi : rm.getProvidesList()) {
          if (pi.isRegularHandler()) {
            String confl = pi.getId();
            for (InterfaceDescriptor mi : md.getProvidesList()) {
              if (mi.getId().equals(confl)
                && mi.isRegularHandler()
                && modsEnabled.containsKey(runningmodule)) {
                logger.info("resolveModuleConflicts remove " + runningmodule);
                TenantModuleDescriptor tm = new TenantModuleDescriptor();
                tm.setAction("disable");
                tm.setId(runningmodule);
                tml.add(tm);
                modsEnabled.remove(runningmodule);
                it = modsEnabled.keySet().iterator();
                v++;
              }
            }
          }
        }
      }
    }
    return v;
  }

  public int addModuleDependencies(ModuleDescriptor md,
    Map<String, ModuleDescriptor> modsAvailable, Map<String, ModuleDescriptor> modsEnabled,
    List<TenantModuleDescriptor> tml) {
    int sum = 0;
    logger.info("addModuleDependencies " + md.getId());
    for (InterfaceDescriptor req : md.getRequiresList()) {
      int v = checkInterfaceDependency(req, modsAvailable, modsEnabled, tml);
      if (v == -1) {
        return v;
      }
      sum += v;
    }
    List<ModuleDescriptor> fromModule = new LinkedList<>();
    sum += resolveModuleConflicts(md, modsEnabled, tml, fromModule);

    logger.info("addModuleDependencies - add " + md.getId());
    modsEnabled.put(md.getId(), md);
    TenantModuleDescriptor tm = new TenantModuleDescriptor();
    tm.setAction("enable");
    if (!fromModule.isEmpty()) {
      tm.setFrom(fromModule.get(0).getId());
    }
    tm.setId(md.getId());
    tml.add(tm);
    return sum + 1;
  }

  public int removeModuleDependencies(ModuleDescriptor md,
    Map<String, ModuleDescriptor> modsEnabled,
    List<TenantModuleDescriptor> tml) {
    int sum = 0;
    logger.info("removeModuleDependencies " + md.getId());

    if (!modsEnabled.containsKey(md.getId())) {
      return 0;
    }
    InterfaceDescriptor[] provides = md.getProvidesList();
    for (InterfaceDescriptor prov : provides) {
      if (prov.isRegularHandler()) {
        Iterator<String> it = modsEnabled.keySet().iterator();
        while (it.hasNext()) {
          String runningmodule = it.next();
          ModuleDescriptor rm = modsEnabled.get(runningmodule);
          InterfaceDescriptor[] requires = rm.getRequiresList();
          for (InterfaceDescriptor ri : requires) {
            if (prov.getId().equals(ri.getId())) {
              sum += removeModuleDependencies(rm, modsEnabled, tml);
              it = modsEnabled.keySet().iterator();
            }
          }
        }
      }
    }
    modsEnabled.remove(md.getId());
    TenantModuleDescriptor tm = new TenantModuleDescriptor();
    tm.setAction("disable");
    tm.setId(md.getId());
    tml.add(tm);
    return sum + 1;
  }

  /**
   * Check that all dependencies are satisfied. Usually called with a copy of
   * the modules list, after making some change.
   *
   * @param modlist list to check
   * @return error message, or "" if all is ok
   */
  public String checkAllDependencies(Map<String, ModuleDescriptor> modlist) {
    for (ModuleDescriptor md : modlist.values()) {
      String res = checkDependencies(md, modlist);
      if (!res.isEmpty()) {
        return res;
      }
    }
    return "";
  }

  /**
   * Check a module list for conflicts.
   *
   * @param modlist modules to be checked
   * @return error message listing conflicts, or "" if no problems
   */
  public String checkAllConflicts(Map<String, ModuleDescriptor> modlist) {
    Map<String, String> provs = new HashMap<>(); // interface name to module name
    StringBuilder conflicts = new StringBuilder();
    for (ModuleDescriptor md : modlist.values()) {
      InterfaceDescriptor[] provides = md.getProvidesList();
      for (InterfaceDescriptor mi : provides) {
        if (mi.isRegularHandler()) {
          String confl = provs.get(mi.getId());
          if (confl == null || confl.isEmpty()) {
            provs.put(mi.getId(), md.getId());
          } else {
            String msg = "Interface " + mi.getId()
              + " is provided by " + md.getId() + " and " + confl + ". ";
            conflicts.append(msg);
          }
        }
      }
    }
    logger.debug("checkAllConflicts: " + conflicts.toString());
    return conflicts.toString();
  }

  /**
   * Create a module.
   *
   * @param md
   * @param fut
   */
  public void create(ModuleDescriptor md, boolean check, Handler<ExtendedAsyncResult<Void>> fut) {
    List<ModuleDescriptor> l = new LinkedList<>();
    l.add(md);
    createList(l, check, fut);
  }

  /**
   * Create a whole list of modules.
   *
   * @param list
   * @param fut
   */
  public void createList(List<ModuleDescriptor> list, boolean check, Handler<ExtendedAsyncResult<Void>> fut) {
    modules.getAll(ares -> {
      if (ares.failed()) {
        fut.handle(new Failure<>(ares.getType(), ares.cause()));
        return;
      }
      LinkedHashMap<String, ModuleDescriptor> tempList = ares.result();
      LinkedList<ModuleDescriptor> nList = new LinkedList<>();
      for (ModuleDescriptor md : list) {
        final String id = md.getId();
        if (tempList.containsKey(id)) {
          ModuleDescriptor exMd = tempList.get(id);

          String exJson = Json.encodePrettily(exMd);
          String json = Json.encodePrettily(md);
          if (!json.equals(exJson)) {
            fut.handle(new Failure<>(USER, "create: module " + id + " exists already"));
            return;
          }
        } else {
          tempList.put(id, md);
          nList.add(md);
        }
      }
      if (check) {
        String res = checkAllDependencies(tempList);
        if (!res.isEmpty()) {
          fut.handle(new Failure<>(USER, res));
          return;
        }
      }
      createList2(nList, fut);
    });
  }

  private void createList2(List<ModuleDescriptor> list, Handler<ExtendedAsyncResult<Void>> fut) {
    CompList<Void> futures = new CompList<>(INTERNAL);
    for (ModuleDescriptor md : list) {
      Future<Void> f = Future.future();
      createList3(md, f::handle);
      futures.add(f);
    }
    futures.all(fut);
  }

  private void createList3(ModuleDescriptor md, Handler<ExtendedAsyncResult<Void>> fut) {
    String id = md.getId();
    if (moduleStore == null) {
      modules.add(id, md, ares -> {
        if (ares.failed()) {
          fut.handle(new Failure<>(ares.getType(), ares.cause()));
          return;
        }
        fut.handle(new Success<>());
      });
    } else {
      moduleStore.insert(md, ires -> {
        if (ires.failed()) {
          fut.handle(new Failure<>(ires.getType(), ires.cause()));
          return;
        }
        modules.add(id, md, ares -> {
          if (ares.failed()) {
            fut.handle(new Failure<>(ares.getType(), ares.cause()));
            return;
          }
          fut.handle(new Success<>());
        });
      });
    }
  }

  /**
   * Update a module.
   *
   * @param md
   * @param fut
   */
  public void update(ModuleDescriptor md, Handler<ExtendedAsyncResult<Void>> fut) {
    final String id = md.getId();
    modules.getAll(ares -> {
      if (ares.failed()) {
        fut.handle(new Failure<>(ares.getType(), ares.cause()));
        return;
      }
      LinkedHashMap<String, ModuleDescriptor> tempList = ares.result();
      tempList.put(id, md);
      String res = checkAllDependencies(tempList);
      if (!res.isEmpty()) {
        fut.handle(new Failure<>(USER, "update: module " + id + ": " + res));
        return;
      }
      tenantManager.getModuleUser(id, gres -> {
        if (gres.failed()) {
          if (gres.getType() == ANY) {
            String ten = gres.cause().getMessage();
            fut.handle(new Failure<>(USER, "update: module " + id
              + " is used by tenant " + ten));
          } else { // any other error
            fut.handle(new Failure<>(gres.getType(), gres.cause()));
          }
          return;
        }
        // all ok, we can update it
        if (moduleStore == null) { // no db, just upd shared memory
          modules.put(id, md, fut);
        } else {
          moduleStore.update(md, ures -> { // store in db first,
            if (ures.failed()) {
              fut.handle(new Failure<>(ures.getType(), ures.cause()));
            } else {
              modules.put(id, md, fut);
            }
          });
        }
      }); // getModuleUser
    }); // get
  }

  /**
   * Delete a module.
   *
   * @param id
   * @param fut
   */
  public void delete(String id, Handler<ExtendedAsyncResult<Void>> fut) {
    modules.getAll(ares -> {
      if (ares.failed()) {
        fut.handle(new Failure<>(ares.getType(), ares.cause()));
        return;
      }
      if (deleteCheckDep(id, fut, ares.result())) {
        return;
      }
      tenantManager.getModuleUser(id, ures -> {
        if (ures.failed()) {
          if (ures.getType() == ANY) {
            String ten = ures.cause().getMessage();
            fut.handle(new Failure<>(USER, "delete: module " + id
              + " is used by tenant " + ten));
          } else {
            fut.handle(new Failure<>(ures.getType(), ures.cause()));
          }
        } else if (moduleStore == null) {
          deleteInternal(id, fut);
        } else {
          moduleStore.delete(id, dres -> {
            if (dres.failed()) {
              fut.handle(new Failure<>(dres.getType(), dres.cause()));
            } else {
              deleteInternal(id, fut);
            }
          });
        }
      });
    });
  }

  private boolean deleteCheckDep(String id, Handler<ExtendedAsyncResult<Void>> fut,
    LinkedHashMap<String, ModuleDescriptor> mods) {

    if (!mods.containsKey(id)) {
      fut.handle(new Failure<>(NOT_FOUND, "delete: module does not exist"));
      return true;
    }
    mods.remove(id);
    String res = checkAllDependencies(mods);
    if (!res.isEmpty()) {
      fut.handle(new Failure<>(USER, "delete: module " + id + ": " + res));
      return true;
    } else {
      return false;
    }
  }

  private void deleteInternal(String id, Handler<ExtendedAsyncResult<Void>> fut) {
    modules.remove(id, rres -> {
      if (rres.failed()) {
        fut.handle(new Failure<>(rres.getType(), rres.cause()));
      } else {
        fut.handle(new Success<>());
      }
    });
  }

  /**
   * Get a module.
   *
   * @param id to get. If null, returns a null.
   * @param fut
   */
  public void get(String id, Handler<ExtendedAsyncResult<ModuleDescriptor>> fut) {
    if (id != null) {
      modules.get(id, fut);
    } else {
      fut.handle(new Success<>(null));
    }
  }

  public void getLatest(String id, Handler<ExtendedAsyncResult<ModuleDescriptor>> fut) {
    ModuleId moduleId = id != null ? new ModuleId(id) : null;
    if (moduleId == null || moduleId.hasSemVer()) {
      get(id, fut);
    } else {
      modules.getKeys(res2 -> {
        if (res2.failed()) {
          fut.handle(new Failure<>(res2.getType(), res2.cause()));
        } else {
          String latest = moduleId.getLatest(res2.result());
          get(latest, fut);
        }
      });
    }
  }

  public void getModulesWithFilter(ModuleId filter, boolean preRelease,
    Handler<ExtendedAsyncResult<List<ModuleDescriptor>>> fut) {
    modules.getAll(kres -> {
      if (kres.failed()) {
        fut.handle(new Failure<>(kres.getType(), kres.cause()));
      } else {
        List<ModuleDescriptor> mdl = new LinkedList<>();
        for (ModuleDescriptor md : kres.result().values()) {
          String id = md.getId();
          ModuleId idThis = new ModuleId(id);
          if ((filter == null || idThis.hasPrefix(filter))
            && (preRelease || !idThis.hasPreRelease())) {
            mdl.add(md);
          }
        }
        fut.handle(new Success<>(mdl));
      }
    });
  }

  /**
   * Get all modules that are enabled for the given tenant.
   *
   * @param ten tenant to check for
   * @param fut callback with a list of ModuleDescriptors (may be empty list)
   */
  public void getEnabledModules(Tenant ten,
    Handler<ExtendedAsyncResult<List<ModuleDescriptor>>> fut) {

    List<ModuleDescriptor> mdl = new LinkedList<>();
    CompList<List<ModuleDescriptor>> futures = new CompList<>(INTERNAL);
    for (String id : ten.getEnabled().keySet()) {
      Future<ModuleDescriptor> f = Future.future();
      modules.get(id, res -> {
        if (res.succeeded()) {
          mdl.add(res.result());
        }
        f.handle(res);
      });
      futures.add(f);
    }
    futures.all(mdl, fut);
  }
}
