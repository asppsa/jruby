package org.jruby.ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jruby.ParseResult;
import org.jruby.RubyInstanceConfig;

import org.jruby.RubyModule;
import org.jruby.ir.dataflow.DataFlowProblem;
import org.jruby.ir.instructions.BreakInstr;
import org.jruby.ir.instructions.CallBase;
import org.jruby.ir.instructions.CopyInstr;
import org.jruby.ir.instructions.DefineMetaClassInstr;
import org.jruby.ir.instructions.GetGlobalVariableInstr;
import org.jruby.ir.instructions.Instr;
import org.jruby.ir.instructions.NonlocalReturnInstr;
import org.jruby.ir.instructions.PutGlobalVarInstr;
import org.jruby.ir.instructions.ReceiveSelfInstr;
import org.jruby.ir.instructions.ResultInstr;
import org.jruby.ir.instructions.Specializeable;
import org.jruby.ir.instructions.ThreadPollInstr;
import org.jruby.ir.instructions.ReceiveKeywordArgInstr;
import org.jruby.ir.instructions.ReceiveKeywordRestArgInstr;
import org.jruby.ir.listeners.IRScopeListener;
import org.jruby.ir.operands.BooleanLiteral;
import org.jruby.ir.operands.Float;
import org.jruby.ir.operands.GlobalVariable;
import org.jruby.ir.operands.Label;
import org.jruby.ir.operands.LocalVariable;
import org.jruby.ir.operands.Operand;
import org.jruby.ir.operands.Self;
import org.jruby.ir.operands.TemporaryCurrentModuleVariable;
import org.jruby.ir.operands.TemporaryCurrentScopeVariable;
import org.jruby.ir.operands.TemporaryBooleanVariable;
import org.jruby.ir.operands.TemporaryFloatVariable;
import org.jruby.ir.operands.TemporaryLocalReplacementVariable;
import org.jruby.ir.operands.TemporaryLocalVariable;
import org.jruby.ir.operands.TemporaryVariableType;
import org.jruby.ir.operands.Variable;
import org.jruby.ir.passes.AddLocalVarLoadStoreInstructions;
import org.jruby.ir.passes.CompilerPass;
import org.jruby.ir.passes.CompilerPassScheduler;
import org.jruby.ir.passes.DeadCodeElimination;
import org.jruby.ir.persistence.IRReaderDecoder;
import org.jruby.ir.passes.UnboxingPass;
import org.jruby.ir.representations.BasicBlock;
import org.jruby.ir.representations.CFG;
import org.jruby.ir.representations.CFGLinearizer;
import org.jruby.ir.transformations.inlining.CFGInliner;
import org.jruby.parser.StaticScope;
import org.jruby.util.log.Logger;
import org.jruby.util.log.LoggerFactory;

/**
 * Right now, this class abstracts the following execution scopes:
 * Method, Closure, Module, Class, MetaClass
 * Top-level Script, and Eval Script
 *
 * In the compiler-land, IR versions of these scopes encapsulate only as much
 * information as is required to convert Ruby code into equivalent Java code.
 *
 * But, in the non-compiler land, there will be a corresponding java object for
 * some of these scopes which encapsulates the runtime semantics and data needed
 * for implementing them.  In the case of Module, Class, MetaClass, and Method,
 * they also happen to be instances of the corresponding Ruby classes -- so,
 * in addition to providing code that help with this specific ruby implementation,
 * they also have code that let them behave as ruby instances of their corresponding
 * classes.
 *
 * Examples:
 * - the runtime class object might have refs. to the runtime method objects.
 * - the runtime method object might have a slot for a heap frame (for when it
 *   has closures that need access to the method's local variables), it might
 *   have version information, it might have references to other methods that
 *   were optimized with the current version number, etc.
 * - the runtime closure object will have a slot for a heap frame (for when it
 *   has closures within) and might get reified as a method in the java land
 *   (but inaccessible in ruby land).  So, passing closures in Java land might
 *   be equivalent to passing around the method handles.
 *
 * and so on ...
 */
public abstract class IRScope implements ParseResult {
    private static final Logger LOG = LoggerFactory.getLogger("IRScope");

    private static Integer globalScopeCount = 0;

    /** Unique global scope id */
    private int scopeId;

    /** Name */
    private String name;

    /** File within which this scope has been defined */
    private final String fileName;

    /** Starting line for this scope's definition */
    private final int lineNumber;

    /** Lexical parent scope */
    private IRScope lexicalParent;

    /** List of (nested) closures in this scope */
    private List<IRClosure> nestedClosures;

    // Index values to guarantee we don't assign same internal index twice
    private int nextClosureIndex;

    // List of all scopes this scope contains lexically.  This is not used
    // for execution, but is used during dry-runs for debugging.
    private List<IRScope> lexicalChildren;

    /** Parser static-scope that this IR scope corresponds to */
    private StaticScope staticScope;

    /** List of IR instructions for this method */
    private List<Instr> instrList;

    /** Control flow graph representation of this method's instructions */
    private CFG cfg;

    /** Local variables defined in this scope */
    private Set<Variable> definedLocalVars;

    /** Local variables used in this scope */
    private Set<Variable> usedLocalVars;

    /** Is %block implicit block arg unused? */
    private boolean hasUnusedImplicitBlockArg;

    /** Map of name -> dataflow problem */
    private Map<String, DataFlowProblem> dfProbs;

    private Instr[] linearizedInstrArray;
    private List<BasicBlock> linearizedBBList;
    private Map<Integer, Integer> rescueMap;
    protected int temporaryVariableIndex;
    protected int floatVariableIndex;

    /** Keeps track of types of prefix indexes for variables and labels */
    private Map<String, Integer> nextVarIndex;

    private int instructionsOffsetInfoPersistenceBuffer = -1;
    private IRReaderDecoder persistenceStore = null;
    private TemporaryLocalVariable currentModuleVariable;
    private TemporaryLocalVariable currentScopeVariable;
    private HashMap<Label,Integer> labelIPCMap;

    Map<String, LocalVariable> localVars;
    Map<String, LocalVariable> evalScopeVars;

    /** Have scope flags been computed? */
    private boolean flagsComputed;

    /* *****************************************************************************************************
     * Does this execution scope (applicable only to methods) receive a block and use it in such a way that
     * all of the caller's local variables need to be materialized into a heap binding?
     * Ex:
     *    def foo(&b)
     *      eval 'puts a', b
     *    end
     *
     *    def bar
     *      a = 1
     *      foo {} # prints out '1'
     *    end
     *
     * Here, 'foo' can access all of bar's variables because it captures the caller's closure.
     *
     * There are 2 scenarios when this can happen (even this is conservative -- but, good enough for now)
     * 1. This method receives an explicit block argument (in this case, the block can be stored, passed around,
     *    eval'ed against, called, etc.).
     *    CAVEAT: This is conservative ... it may not actually be stored & passed around, evaled, called, ...
     * 2. This method has a 'super' call (ZSuper AST node -- ZSuperInstr IR instruction)
     *    In this case, the parent (in the inheritance hierarchy) can access the block and store it, etc.  So, in reality,
     *    rather than assume that the parent will always do this, we can query the parent, if we can precisely identify
     *    the parent method (which in the face of Ruby's dynamic hierarchy, we cannot).  So, be pessimistic.
     *
     * This logic was extracted from an email thread on the JRuby mailing list -- Yehuda Katz & Charles Nutter
     * contributed this analysis above.
     * ********************************************************************************************************/
    private boolean canCaptureCallersBinding;

    /* ****************************************************************************
     * Does this scope define code, i.e. does it (or anybody in the downward call chain)
     * do class_eval, module_eval? In the absence of any other information, we default
     * to yes -- which basically leads to pessimistic but safe optimizations.  But, for
     * library and internal methods, this might be false.
     * **************************************************************************** */
    private boolean canModifyCode;

    /* ****************************************************************************
     * Does this scope require a binding to be materialized?
     * Yes if any of the following holds true:
     * - calls 'Proc.new'
     * - calls 'eval'
     * - calls 'call' (could be a call on a stored block which could be local!)
     * - calls 'send' and we cannot resolve the message (method name) that is being sent!
     * - calls methods that can access the caller's binding
     * - calls a method which we cannot resolve now!
     * - has a call whose closure requires a binding
     * **************************************************************************** */
    private boolean bindingHasEscaped;

    /** Does this scope call any eval */
    private boolean usesEval;

    /** Does this scope receive keyword args? */
    private boolean receivesKeywordArgs;

    /** Does this scope have a break instr? */
    protected boolean hasBreakInstrs;

    /** Can this scope receive breaks */
    protected boolean canReceiveBreaks;

    /** Does this scope have a non-local return instr? */
    protected boolean hasNonlocalReturns;

    /** Can this scope receive a non-local return? */
    public boolean canReceiveNonlocalReturns;

    /** Since backref ($~) and lastline ($_) vars are allocated space on the dynamic scope,
     * this is an useful flag to compute. */
    private boolean usesBackrefOrLastline;

    /** Does this scope call any zsuper */
    private boolean usesZSuper;

    /** Does this scope have loops? */
    private boolean hasLoops;

    /** # of thread poll instrs added to this scope */
    private int threadPollInstrsCount;

    /** Does this scope have explicit call protocol instructions?
     *  If yes, there are IR instructions for managing bindings/frames, etc.
     *  If not, this has to be managed implicitly as in the current runtime
     *  For now, only dyn-scopes are managed explicitly.
     *  Others will come in time */
    private boolean hasExplicitCallProtocol;

    /** Should we re-run compiler passes -- yes after we've inlined, for example */
    private boolean relinearizeCFG;

    private IRManager manager;

    // Used by cloning code
    protected IRScope(IRScope s, IRScope lexicalParent) {
        this.lexicalParent = lexicalParent;
        this.manager = s.manager;
        this.fileName = s.fileName;
        this.lineNumber = s.lineNumber;
        this.staticScope = s.staticScope;
        this.threadPollInstrsCount = s.threadPollInstrsCount;
        this.nextClosureIndex = s.nextClosureIndex;
        this.temporaryVariableIndex = s.temporaryVariableIndex;
        this.floatVariableIndex = s.floatVariableIndex;
        this.hasLoops = s.hasLoops;
        this.hasUnusedImplicitBlockArg = s.hasUnusedImplicitBlockArg;
        this.instrList = new ArrayList<Instr>();
        this.nestedClosures = new ArrayList<IRClosure>();
        this.dfProbs = new HashMap<String, DataFlowProblem>();
        this.nextVarIndex = new HashMap<String, Integer>(); // SSS FIXME: clone!
        this.cfg = null;
        this.linearizedInstrArray = null;
        this.linearizedBBList = null;

        this.flagsComputed = s.flagsComputed;
        this.canModifyCode = s.canModifyCode;
        this.canCaptureCallersBinding = s.canCaptureCallersBinding;
        this.receivesKeywordArgs = s.receivesKeywordArgs;
        this.hasBreakInstrs = s.hasBreakInstrs;
        this.hasNonlocalReturns = s.hasNonlocalReturns;
        this.canReceiveBreaks = s.canReceiveBreaks;
        this.canReceiveNonlocalReturns = s.canReceiveNonlocalReturns;
        this.bindingHasEscaped = s.bindingHasEscaped;
        this.usesEval = s.usesEval;
        this.usesBackrefOrLastline = s.usesBackrefOrLastline;
        this.usesZSuper = s.usesZSuper;
        this.hasExplicitCallProtocol = s.hasExplicitCallProtocol;

        this.localVars = new HashMap<String, LocalVariable>(s.localVars);
        this.relinearizeCFG = false;

        setupLexicalContainment();
    }

    public IRScope(IRManager manager, IRScope lexicalParent, String name,
            String fileName, int lineNumber, StaticScope staticScope) {
        this.manager = manager;
        this.lexicalParent = lexicalParent;
        this.name = name;
        this.fileName = fileName;
        this.lineNumber = lineNumber;
        this.staticScope = staticScope;
        this.threadPollInstrsCount = 0;
        this.nextClosureIndex = 0;
        this.temporaryVariableIndex = -1;
        this.floatVariableIndex = -1;
        this.instrList = new ArrayList<Instr>();
        this.nestedClosures = new ArrayList<IRClosure>();
        this.dfProbs = new HashMap<String, DataFlowProblem>();
        this.nextVarIndex = new HashMap<String, Integer>();
        this.cfg = null;
        this.linearizedInstrArray = null;
        this.linearizedBBList = null;
        this.hasLoops = false;
        this.hasUnusedImplicitBlockArg = false;

        this.flagsComputed = false;
        this.receivesKeywordArgs = false;
        this.hasBreakInstrs = false;
        this.hasNonlocalReturns = false;
        this.canReceiveBreaks = false;
        this.canReceiveNonlocalReturns = false;

        // These flags are true by default!
        this.canModifyCode = true;
        this.canCaptureCallersBinding = true;
        this.bindingHasEscaped = true;
        this.usesEval = true;
        this.usesBackrefOrLastline = true;
        this.usesZSuper = true;

        this.hasExplicitCallProtocol = false;

        this.localVars = new HashMap<String, LocalVariable>();
        synchronized(globalScopeCount) { this.scopeId = globalScopeCount++; }
        this.relinearizeCFG = false;

        setupLexicalContainment();
    }

    private final void setupLexicalContainment() {
        if (manager.isDryRun() || RubyInstanceConfig.IR_WRITING) {
            lexicalChildren = new ArrayList<IRScope>();
            if (lexicalParent != null) lexicalParent.addChildScope(this);
        }
    }

    private boolean hasListener() {
        return manager.getIRScopeListener() != null;
    }

    public int getScopeId() {
        return scopeId;
    }

    @Override
    public int hashCode() {
        return scopeId;
    }

    @Override
    public boolean equals(Object other) {
        if (other == null || getClass() != other.getClass()) return false;

        return scopeId == ((IRScope) other).scopeId;
    }

    protected void addChildScope(IRScope scope) {
        lexicalChildren.add(scope);
    }

    public List<IRScope> getLexicalScopes() {
        return lexicalChildren;
    }

    public void initNestedClosures() {
        this.nestedClosures = new ArrayList<IRClosure>();
    }

    public void addClosure(IRClosure c) {
        nestedClosures.add(c);
    }

    public Instr getLastInstr() {
        return instrList.get(instrList.size() - 1);
    }

    public void addInstrAtBeginning(Instr i) {
        if (hasListener()) {
            IRScopeListener listener = manager.getIRScopeListener();
            listener.addedInstr(this, i, 0);
        }
        instrList.add(0, i);
    }

    public void addInstr(Instr i) {
        // SSS FIXME: If more instructions set these flags, there may be
        // a better way to do this by encoding flags in its own object
        // and letting every instruction update it.
        if (i instanceof ThreadPollInstr) threadPollInstrsCount++;
        else if (i instanceof BreakInstr) this.hasBreakInstrs = true;
        else if (i instanceof NonlocalReturnInstr) this.hasNonlocalReturns = true;
        else if (i instanceof DefineMetaClassInstr) this.canReceiveNonlocalReturns = true;
        else if (i instanceof ReceiveKeywordArgInstr || i instanceof ReceiveKeywordRestArgInstr) this.receivesKeywordArgs = true;
        if (hasListener()) {
            IRScopeListener listener = manager.getIRScopeListener();
            listener.addedInstr(this, i, instrList.size());
        }
        instrList.add(i);
    }

    public LocalVariable getNewFlipStateVariable() {
        return getLocalVariable("%flip_" + allocateNextPrefixedName("%flip"), 0);
    }

    public void initFlipStateVariable(Variable v, Operand initState) {
        // Add it to the beginning
        instrList.add(0, new CopyInstr(v, initState));
    }

    public Label getNewLabel(String prefix) {
        return new Label(prefix, allocateNextPrefixedName(prefix));
    }

    public Label getNewLabel() {
        return getNewLabel("LBL");
    }

    public List<IRClosure> getClosures() {
        return nestedClosures;
    }

    public IRManager getManager() {
        return manager;
    }

    /**
     *  Returns the lexical scope that contains this scope definition
     */
    public IRScope getLexicalParent() {
        return lexicalParent;
    }

    public StaticScope getStaticScope() {
        return staticScope;
    }

    public IRMethod getNearestMethod() {
        IRScope current = this;

        while (current != null && !(current instanceof IRMethod)) {
            current = current.getLexicalParent();
        }

        return (IRMethod) current;
    }

    public IRScope getNearestFlipVariableScope() {
        IRScope current = this;

        while (current != null && !current.isFlipScope()) {
            current = current.getLexicalParent();
        }

        return current;
    }

    public IRScope getNearestTopLocalVariableScope() {
        IRScope current = this;

        while (current != null && !current.isTopLocalVariableScope()) {
            current = current.getLexicalParent();
        }

        return current;
    }

    /**
     * Returns the nearest scope which we can extract a live module from.  If
     * this returns null (like for evals), then it means it cannot be statically
     * determined.
     */
    public IRScope getNearestModuleReferencingScope() {
        IRScope current = this;

        while (!(current instanceof IRModuleBody)) {
            // When eval'ing, we dont have a lexical view of what module we are nested in
            // because binding_eval, class_eval, module_eval, instance_eval can switch
            // around the lexical scope for evaluation to be something else.
            if (current == null || current instanceof IREvalScript) return null;

            current = current.getLexicalParent();
        }

        return current;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) { // This is for IRClosure and IRMethod ;(
        this.name = name;
    }

    public String getFileName() {
        return fileName;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    /**
     * Returns the top level scope
     */
    public IRScope getTopLevelScope() {
        IRScope current = this;

        for (; current != null && !current.isScriptScope(); current = current.getLexicalParent()) {}

        return current;
    }

    public boolean isNestedInClosure(IRClosure closure) {
        for (IRScope s = this; s != null && !s.isTopLocalVariableScope(); s = s.getLexicalParent()) {
            if (s == closure) return true;
        }

        return false;
    }

    public void setHasLoopsFlag(boolean f) {
        hasLoops = true;
    }

    public boolean hasLoops() {
        return hasLoops;
    }

    public boolean hasExplicitCallProtocol() {
        return hasExplicitCallProtocol;
    }

    public void setExplicitCallProtocolFlag(boolean flag) {
        this.hasExplicitCallProtocol = flag;
    }

    public void setCodeModificationFlag(boolean f) {
        canModifyCode = f;
    }

    public boolean receivesKeywordArgs() {
        return this.receivesKeywordArgs;
    }

    public boolean modifiesCode() {
        return canModifyCode;
    }

    public boolean bindingHasEscaped() {
        return bindingHasEscaped;
    }

    public boolean usesBackrefOrLastline() {
        return usesBackrefOrLastline;
    }

    public boolean usesEval() {
        return usesEval;
    }

    public boolean usesZSuper() {
        return usesZSuper;
    }

    public boolean canCaptureCallersBinding() {
        return canCaptureCallersBinding;
    }

    public boolean canReceiveNonlocalReturns() {
        if (this.canReceiveNonlocalReturns) {
            return true;
        }

        boolean canReceiveNonlocalReturns = false;
        for (IRClosure cl : getClosures()) {
            if (cl.hasNonlocalReturns || cl.canReceiveNonlocalReturns()) {
                canReceiveNonlocalReturns = true;
            }
        }
        return canReceiveNonlocalReturns;
    }

    public CFG buildCFG() {
        CFG newCFG = new CFG(this);
        newCFG.build(getInstrs());
        // Clear out instruction list after CFG has been built.
        this.instrList = null;

        setCFG(newCFG);

        return newCFG;
    }

    protected void setCFG(CFG cfg) {
       this.cfg = cfg;
    }

    public CFG getCFG() {
        return cfg;
    }

    private Instr[] prepareInstructionsForInterpretation() {
        checkRelinearization();

        if (linearizedInstrArray != null) return linearizedInstrArray; // Already prepared

        setupLinearization();

        // Set up IPCs
        List<Instr> newInstrs = new ArrayList<Instr>();
        labelIPCMap = new HashMap<Label, Integer>();
        int ipc = 0;
        for (BasicBlock b: linearizedBBList) {
            Label l = b.getLabel();
            labelIPCMap.put(l, ipc);
            // This assumes if multiple equal/same labels exist which are scattered around the scope
            // must be the same Java instance or only this one will get a targetPC set.
            l.setTargetPC(ipc);
            List<Instr> bbInstrs = b.getInstrs();
            int bbInstrsLength = bbInstrs.size();
            for (int i = 0; i < bbInstrsLength; i++) {
                Instr instr = bbInstrs.get(i);

                if (instr instanceof Specializeable) {
                    instr = ((Specializeable) instr).specializeForInterpretation();
                    bbInstrs.set(i, instr);
                }

                if (!(instr instanceof ReceiveSelfInstr)) {
                    newInstrs.add(instr);
                    instr.setIPC(ipc);
                    ipc++;
                }
            }
        }

        // System.out.println("SCOPE: " + getName());
        // System.out.println("INSTRS: " + cfg().toStringInstrs());

        // Exit BB ipc
        cfg().getExitBB().getLabel().setTargetPC(ipc + 1);

        // Set up rescue map
        setupRescueMap();

        linearizedInstrArray = newInstrs.toArray(new Instr[newInstrs.size()]);
        return linearizedInstrArray;
    }

    public void setupRescueMap() {
        this.rescueMap = new HashMap<Integer, Integer>();
        for (BasicBlock b : linearizedBBList) {
            BasicBlock rescuerBB = cfg().getRescuerBBFor(b);
            int rescuerPC = (rescuerBB == null) ? -1 : rescuerBB.getLabel().getTargetPC();
            for (Instr i : b.getInstrs()) {
                rescueMap.put(i.getIPC(), rescuerPC);
            }
        }
    }

    private void runCompilerPasses(List<CompilerPass> passes) {
        // SSS FIXME: Why is this again?  Document this weirdness!
        // Forcibly clear out the shared eval-scope variable allocator each time this method executes
        initEvalScopeVariableAllocator(true);
        // SSS FIXME: We should configure different optimization levels
        // and run different kinds of analysis depending on time budget.  Accordingly, we need to set
        // IR levels/states (basic, optimized, etc.) and the
        // ENEBO: If we use a MT optimization mechanism we cannot mutate CFG
        // while another thread is using it.  This may need to happen on a clone()
        // and we may need to update the method to return the new method.  Also,
        // if this scope is held in multiple locations how do we update all references?
        CompilerPassScheduler scheduler = getManager().schedulePasses(passes);
        for (CompilerPass pass: scheduler) {
            pass.run(this);
        }

        CompilerPass pass;

        if (RubyInstanceConfig.IR_UNBOXING) {
            pass = new UnboxingPass();
            pass.run(this);
        }
    }

    private void runDeadCodeAndVarLoadStorePasses() {
        CompilerPass pass;// For methods with unescaped bindings, inline the binding
        // by converting local var loads/store to tmp var loads/stores
        if (this instanceof IRMethod && !this.bindingHasEscaped()) {
            pass = new DeadCodeElimination();
            if (pass.previouslyRun(this) == null) {
                pass.run(this);
            }
            pass = new AddLocalVarLoadStoreInstructions();
            if (pass.previouslyRun(this) == null) {
                pass.run(this);
            }
        }
    }

    /** Run any necessary passes to get the IR ready for interpretation */
    public synchronized Instr[] prepareForInterpretation(boolean isLambda) {
        if (isLambda) {
            // Add a global ensure block to catch uncaught breaks
            // and throw a LocalJumpError.
            if (((IRClosure)this).addGEBForUncaughtBreaks()) {
                this.relinearizeCFG = true;
            }
        }

        checkRelinearization();

        if (linearizedInstrArray != null) return linearizedInstrArray;

        // Build CFG and run compiler passes, if necessary
        if (getCFG() == null) {
            runCompilerPasses(getManager().getCompilerPasses(this));

            // run DCE and var load/store
            runDeadCodeAndVarLoadStorePasses();
        }

        // Linearize CFG, etc.
        return prepareInstructionsForInterpretation();
    }

    /* SSS FIXME: Do we need to synchronize on this?  Cache this info in a scope field? */
    /** Run any necessary passes to get the IR ready for compilation */
    public Tuple<Instr[], Map<Integer,Label[]>> prepareForCompilation() {
        // Build CFG and run compiler passes, if necessary
        if (getCFG() == null) {
            runCompilerPasses(getManager().getJITPasses(this));

            // no DCE for now to stress-test JIT
            //runDeadCodeAndVarLoadStorePasses();
        }

        // Add this always since we dont re-JIT a previously
        // JIT-ted closure.  But, check if there are other
        // smarts available to us and eliminate adding this
        // code to every closure there is.
        //
        // Add a global ensure block to catch uncaught breaks
        // and throw a LocalJumpError.
        if (this instanceof IRClosure && ((IRClosure)this).addGEBForUncaughtBreaks()) {
            this.relinearizeCFG = true;
        }

        prepareInstructionsForInterpretation();

        // Set up IPCs
        // FIXME: Would be nice to collapse duplicate labels; for now, using Label[]
        HashMap<Integer, Label[]> ipcLabelMap = new HashMap<Integer, Label[]>();
        List<Instr> newInstrs = new ArrayList<Instr>();
        int ipc = 0;
        for (BasicBlock b : linearizedBBList) {
            Label l = b.getLabel();
            ipcLabelMap.put(ipc, catLabels(ipcLabelMap.get(ipc), l));
            for (Instr i : b.getInstrs()) {
                if (!(i instanceof ReceiveSelfInstr)) {
                    newInstrs.add(i);
                    i.setIPC(ipc);
                    ipc++;
                }
            }
        }

        return new Tuple<Instr[], Map<Integer,Label[]>>(newInstrs.toArray(new Instr[newInstrs.size()]), ipcLabelMap);
    }

    private void setupLinearization() {
        try {
            buildLinearization(); // FIXME: compiler passes should have done this
            depends(linearization());
        } catch (RuntimeException e) {
            LOG.error("Error linearizing cfg: ", e);
            CFG c = cfg();
            LOG.error("\nGraph:\n" + c.toStringGraph());
            LOG.error("\nInstructions:\n" + c.toStringInstrs());
            throw e;
        }
    }

    private List<Object[]> buildJVMExceptionTable() {
        List<Object[]> etEntries = new ArrayList<Object[]>();
        for (BasicBlock b: linearizedBBList) {
            BasicBlock rBB = cfg().getRescuerBBFor(b);
            if (rBB != null) {
                etEntries.add(new Object[] {b.getLabel(), rBB.getLabel(), Throwable.class});
            }
        }

        // SSS FIXME: This could be optimized by compressing entries for adjacent BBs that have identical handlers
        // This could be optimized either during generation or as another pass over the table.  But, if the JVM
        // does that already, do we need to bother with it?
        return etEntries;
    }

    private static Label[] catLabels(Label[] labels, Label cat) {
        if (labels == null) return new Label[] {cat};
        Label[] newLabels = new Label[labels.length + 1];
        System.arraycopy(labels, 0, newLabels, 0, labels.length);
        newLabels[labels.length] = cat;
        return newLabels;
    }

    private boolean computeScopeFlags(boolean receivesClosureArg, List<Instr> instrs) {
        for (Instr i: instrs) {
            Operation op = i.getOperation();
            if (op == Operation.RECV_CLOSURE) {
                receivesClosureArg = true;
            } else if (op == Operation.ZSUPER) {
                this.canCaptureCallersBinding = true;
                this.usesZSuper = true;
            } else if (i instanceof CallBase) {
                CallBase call = (CallBase) i;

                if (call.targetRequiresCallersBinding()) this.bindingHasEscaped = true;

                if (call.canBeEval()) {
                    this.usesEval = true;

                    // If this method receives a closure arg, and this call is an eval that has more than 1 argument,
                    // it could be using the closure as a binding -- which means it could be using pretty much any
                    // variable from the caller's binding!
                    if (receivesClosureArg && (call.getCallArgs().length > 1)) {
                        this.canCaptureCallersBinding = true;
                    }
                }
            } else if (op == Operation.GET_GLOBAL_VAR) {
                GlobalVariable gv = (GlobalVariable)((GetGlobalVariableInstr)i).getSource();
                String gvName = gv.getName();
                if (gvName.equals("$_") ||
                    gvName.equals("$~") ||
                    gvName.equals("$`") ||
                    gvName.equals("$'") ||
                    gvName.equals("$+") ||
                    gvName.equals("$LAST_READ_LINE") ||
                    gvName.equals("$LAST_MATCH_INFO") ||
                    gvName.equals("$PREMATCH") ||
                    gvName.equals("$POSTMATCH") ||
                    gvName.equals("$LAST_PAREN_MATCH"))
                {
                    this.usesBackrefOrLastline = true;
                }
            } else if (op == Operation.PUT_GLOBAL_VAR) {
                GlobalVariable gv = (GlobalVariable)((PutGlobalVarInstr)i).getTarget();
                String gvName = gv.getName();
                if (gvName.equals("$_") || gvName.equals("$~")) usesBackrefOrLastline = true;
            } else if (op == Operation.MATCH || op == Operation.MATCH2 || op == Operation.MATCH3) {
                this.usesBackrefOrLastline = true;
            } else if (op == Operation.BREAK) {
                this.hasBreakInstrs = true;
            } else if (i instanceof NonlocalReturnInstr) {
                this.hasNonlocalReturns = true;
            } else if (i instanceof DefineMetaClassInstr) {
                // SSS: Inner-classes are defined with closures and
                // a return in the closure can force a return from this method
                // For now conservatively assume that a scope with inner-classes
                // can receive non-local returns. (Alternatively, have to inspect
                // all lexically nested scopes, not just closures in computeScopeFlags())
                this.canReceiveNonlocalReturns = true;
            }
        }

        return receivesClosureArg;
    }

    //
    // This can help use eliminate writes to %block that are not used since this is
    // a special local-variable, not programmer-defined local-variable
    public void computeScopeFlags() {
        if (flagsComputed) {
            return;
        }

        // init
        canModifyCode = true;
        canCaptureCallersBinding = false;
        usesZSuper = false;
        usesEval = false;
        usesBackrefOrLastline = false;
        // NOTE: bindingHasEscaped is the crucial flag and it effectively is
        // unconditionally true whenever it has a call that receives a closure.
        // See CallInstr.computeRequiresCallersBindingFlag
        bindingHasEscaped = (this instanceof IREvalScript); // for eval scopes, bindings are considered escaped ...
        hasBreakInstrs = false;
        hasNonlocalReturns = false;
        canReceiveBreaks = false;
        canReceiveNonlocalReturns = false;

        // recompute flags -- we could be calling this method different times
        // definitely once after ir generation and local optimizations propagates constants locally
        // but potentially at a later time after doing ssa generation and constant propagation
        if (cfg == null) {
            computeScopeFlags(false, getInstrs());
        } else {
            boolean receivesClosureArg = false;
            for (BasicBlock b: cfg.getBasicBlocks()) {
                receivesClosureArg = computeScopeFlags(receivesClosureArg, b.getInstrs());
            }
        }

        // Compute flags for nested closures (recursively) and set derived flags.
        for (IRClosure cl : getClosures()) {
            cl.computeScopeFlags();
            if (cl.hasBreakInstrs || cl.canReceiveBreaks) {
                canReceiveBreaks = true;
            }
            if (cl.hasNonlocalReturns || cl.canReceiveNonlocalReturns) {
                canReceiveNonlocalReturns = true;
            }
            if (cl.usesZSuper()) {
                usesZSuper = true;
            }
        }

        flagsComputed = true;
    }

    public abstract IRScopeType getScopeType();

    @Override
    public String toString() {
        return getScopeType() + " " + getName() + "[" + getFileName() + ":" + getLineNumber() + "]";
    }

    public String toStringInstrs() {
        StringBuilder b = new StringBuilder();

        int i = 0;
        for (Instr instr : instrList) {
            if (i > 0) b.append("\n");

            b.append("  ").append(i).append('\t').append(instr);

            i++;
        }

        if (!nestedClosures.isEmpty()) {
            b.append("\n\n------ Closures encountered in this scope ------\n");
            for (IRClosure c: nestedClosures)
                b.append(c.toStringBody());
            b.append("------------------------------------------------\n");
        }

        return b.toString();
    }

    public LocalVariable getSelf() {
        return Self.SELF;
    }

    public Variable getCurrentModuleVariable() {
        // SSS: Used in only 3 cases in generated IR:
        // -> searching a constant in the inheritance hierarchy
        // -> searching a super-method in the inheritance hierarchy
        // -> looking up 'StandardError' (which can be eliminated by creating a special operand type for this)
        if (currentModuleVariable == null) {
            temporaryVariableIndex++;
            currentModuleVariable = new TemporaryCurrentModuleVariable(temporaryVariableIndex);
        }
        return currentModuleVariable;
    }

    public Variable getCurrentScopeVariable() {
        // SSS: Used in only 1 case in generated IR:
        // -> searching a constant in the lexical scope hierarchy
        if (currentScopeVariable == null) {
            temporaryVariableIndex++;
            currentScopeVariable = new TemporaryCurrentScopeVariable(temporaryVariableIndex);
        }
        return currentScopeVariable;
    }

    public abstract LocalVariable getImplicitBlockArg();

    public void markUnusedImplicitBlockArg() {
        hasUnusedImplicitBlockArg = true;
    }

    /**
     * Get the local variables for this scope.
     * This should only be used by persistence layer.
     */
    public Map<String, LocalVariable> getLocalVariables() {
        return localVars;
    }

    /**
     * Set the local variables for this scope. This should only be used by persistence
     * layer.
     */
    // FIXME: Consider making constructor for persistence to pass in all of this stuff
    public void setLocalVariables(Map<String, LocalVariable> variables) {
        this.localVars = variables;
    }

    public void setLabelIndices(Map<String, Integer> indices) {
        nextVarIndex = indices;
    }

    public LocalVariable lookupExistingLVar(String name) {
        return localVars.get(name);
    }

    public LocalVariable findExistingLocalVariable(String name, int depth) {
        return localVars.get(name);
    }

    /**
     * Find or create a local variable.  By default, scopes are assumed to
     * only check current depth.  Blocks/Closures override this because they
     * have special nesting rules.
     */
    public LocalVariable getLocalVariable(String name, int scopeDepth) {
        LocalVariable lvar = findExistingLocalVariable(name, scopeDepth);
        if (lvar == null) {
            lvar = new LocalVariable(name, scopeDepth, localVars.size());
            localVars.put(name, lvar);
        }

        return lvar;
    }

    public LocalVariable getNewLocalVariable(String name, int depth) {
        throw new RuntimeException("getNewLocalVariable should be called for: " + this.getClass().getName());
    }

    protected void initEvalScopeVariableAllocator(boolean reset) {
        if (reset || evalScopeVars == null) evalScopeVars = new HashMap<String, LocalVariable>();
    }

    public TemporaryLocalVariable getNewTemporaryVariable() {
        return getNewTemporaryVariable(TemporaryVariableType.LOCAL);
    }

    public TemporaryLocalVariable getNewTemporaryVariableFor(LocalVariable var) {
        temporaryVariableIndex++;
        return new TemporaryLocalReplacementVariable(var.getName(), temporaryVariableIndex);
    }

    public TemporaryLocalVariable getNewTemporaryVariable(TemporaryVariableType type) {
        switch (type) {
            case FLOAT: {
                floatVariableIndex++;
                return new TemporaryFloatVariable(floatVariableIndex);
            }
            case BOOLEAN: {
                // Shares var index with locals
                temporaryVariableIndex++;
                return new TemporaryBooleanVariable(temporaryVariableIndex);
            }
            case LOCAL: {
                temporaryVariableIndex++;
                return new TemporaryLocalVariable(temporaryVariableIndex);
            }
        }

        throw new RuntimeException("Invalid temporary variable being alloced in this scope: " + type);
    }

    public void setTemporaryVariableCount(int count) {
        temporaryVariableIndex = count + 1;
    }

    public TemporaryLocalVariable getNewUnboxedVariable(Class type) {
        TemporaryVariableType varType;
        if (type == Float.class) {
            varType = TemporaryVariableType.FLOAT;
        } else if (type == BooleanLiteral.class) {
            varType = TemporaryVariableType.BOOLEAN;
        } else {
            varType = TemporaryVariableType.LOCAL;
        }
        return getNewTemporaryVariable(varType);
    }

    public void resetTemporaryVariables() {
        temporaryVariableIndex = -1;
        floatVariableIndex = -1;
    }

    public int getTemporaryVariablesCount() {
        return temporaryVariableIndex + 1;
    }

    public int getFloatVariablesCount() {
        return floatVariableIndex + 1;
    }

    // Generate a new variable for inlined code
    public Variable getNewInlineVariable(String inlinePrefix, Variable v) {
        if (v instanceof LocalVariable) {
            LocalVariable lv = (LocalVariable)v;
            return getLocalVariable(inlinePrefix + lv.getName(), lv.getScopeDepth());
        } else {
            return getNewTemporaryVariable();
        }
    }

    public int getThreadPollInstrsCount() {
        return threadPollInstrsCount;
    }

    public int getLocalVariablesCount() {
        return localVars.size();
    }

    public int getUsedVariablesCount() {
        // System.out.println("For " + this + ", # lvs: " + nextLocalVariableSlot);
        // # local vars, # flip vars
        //
        // SSS FIXME: When we are opting local var access,
        // no need to allocate local var space except when we have been asked to!
        return getLocalVariablesCount() + getPrefixCountSize("%flip");
    }

    public void setUpUseDefLocalVarMaps() {
        definedLocalVars = new java.util.HashSet<Variable>();
        usedLocalVars = new java.util.HashSet<Variable>();
        for (BasicBlock bb : cfg().getBasicBlocks()) {
            for (Instr i : bb.getInstrs()) {
                for (Variable v : i.getUsedVariables()) {
                    if (v instanceof LocalVariable) usedLocalVars.add(v);
                }

                if (i instanceof ResultInstr) {
                    Variable v = ((ResultInstr) i).getResult();

                    if (v instanceof LocalVariable) definedLocalVars.add(v);
                }
            }
        }

        for (IRClosure cl : getClosures()) {
            cl.setUpUseDefLocalVarMaps();
        }
    }

    public boolean usesLocalVariable(Variable v) {
        if (usedLocalVars == null) setUpUseDefLocalVarMaps();
        if (usedLocalVars.contains(v)) return true;

        for (IRClosure cl : getClosures()) {
            if (cl.usesLocalVariable(v)) return true;
        }

        return false;
    }

    public boolean definesLocalVariable(Variable v) {
        if (definedLocalVars == null) setUpUseDefLocalVarMaps();
        if (definedLocalVars.contains(v)) return true;

        for (IRClosure cl : getClosures()) {
            if (cl.definesLocalVariable(v)) return true;
        }

        return false;
    }

    public void setDataFlowSolution(String name, DataFlowProblem p) {
        dfProbs.put(name, p);
    }

    public DataFlowProblem getDataFlowSolution(String name) {
        return dfProbs.get(name);
    }

    // This should only be used to do pre-cfg opts and to build the CFG.
    // Everyone else should use the CFG.
    public List<Instr> getInstrs() {
        if (persistenceStore != null) {
            instrList = persistenceStore.decodeInstructionsAt(this, instructionsOffsetInfoPersistenceBuffer);
        }
        if (cfg != null) throw new RuntimeException("Please use the CFG to access this scope's instructions.");
        return instrList;
    }

    public Instr[] getInstrsForInterpretation() {
        return linearizedInstrArray;
    }

    public Instr[] getInstrsForInterpretation(boolean isLambda) {
        if (linearizedInstrArray == null) {
            prepareForInterpretation(isLambda);
        }
        return linearizedInstrArray;
    }

    public void resetLinearizationData() {
        linearizedBBList = null;
        relinearizeCFG = false;
    }

    public void checkRelinearization() {
        if (relinearizeCFG) resetLinearizationData();
    }

    public List<BasicBlock> buildLinearization() {
        checkRelinearization();

        if (linearizedBBList != null) return linearizedBBList; // Already linearized

        linearizedBBList = CFGLinearizer.linearize(cfg);

        return linearizedBBList;
    }

    public Map<Integer, Integer> getRescueMap() {
        return this.rescueMap;
    }

    public List<BasicBlock> linearization() {
        depends(cfg());

        assert linearizedBBList != null: "You have not run linearization";

        return linearizedBBList;
    }

    protected void depends(Object obj) {
        assert obj != null: "Unsatisfied dependency and this depends() was set " +
                "up wrong.  Use depends(build()) not depends(build).";
    }

    public CFG cfg() {
        assert cfg != null: "Trying to access build before build started";
        return cfg;
    }

    public void resetDFProblemsState() {
        dfProbs = new HashMap<String, DataFlowProblem>();
        for (IRClosure c: nestedClosures) c.resetDFProblemsState();
    }

    public void resetState() {
        relinearizeCFG = true;
        linearizedInstrArray = null;
        cfg.resetState();

        // reset flags
        flagsComputed = false;
        canModifyCode = true;
        canCaptureCallersBinding = true;
        bindingHasEscaped = true;
        usesEval = true;
        usesZSuper = true;
        hasBreakInstrs = false;
        hasNonlocalReturns = false;
        canReceiveBreaks = false;
        canReceiveNonlocalReturns = false;
        rescueMap = null;

        // Reset dataflow problems state
        resetDFProblemsState();
    }

    public void inlineMethod(IRScope method, RubyModule implClass, int classToken, BasicBlock basicBlock, CallBase call, boolean cloneHost) {
        // Inline
        depends(cfg());
        new CFGInliner(cfg).inlineMethod(method, implClass, classToken, basicBlock, call, cloneHost);

        // Reset state
        resetState();

        // Re-run opts
        for (CompilerPass pass: getManager().getInliningCompilerPasses(this)) {
            pass.run(this);
        }
    }

    public void resetCFG() {
        cfg = null;
    }

    /* Record a begin block -- not all scope implementations can handle them */
    public void recordBeginBlock(IRClosure beginBlockClosure) {
        throw new RuntimeException("BEGIN blocks cannot be added to: " + this.getClass().getName());
    }

    /* Record an end block -- not all scope implementations can handle them */
    public void recordEndBlock(IRClosure endBlockClosure) {
        throw new RuntimeException("END blocks cannot be added to: " + this.getClass().getName());
    }

    // Enebo: We should just make n primitive int and not take the hash hit
    protected int allocateNextPrefixedName(String prefix) {
        int index = getPrefixCountSize(prefix);

        nextVarIndex.put(prefix, index + 1);

        return index;
    }

    // This is how IR Persistence can re-read existing saved labels and reset
    // scope back to proper index.
    public void setPrefixedNameIndexTo(String prefix, int newIndex) {
        int index = getPrefixCountSize(prefix);

        nextVarIndex.put(prefix, index);
    }

    protected void resetVariableCounter(String prefix) {
        nextVarIndex.remove(prefix);
    }

    public Map<String, Integer> getVarIndices() {
        return nextVarIndex;
    }

    protected int getPrefixCountSize(String prefix) {
        Integer index = nextVarIndex.get(prefix);

        if (index == null) return 0;

        return index.intValue();
    }

    public int getNextClosureId() {
        nextClosureIndex++;

        return nextClosureIndex;
    }

    public boolean isForLoopBody() {
        return false;
    }

    public boolean isBeginEndBlock() {
        return false;
    }

    /**
     * Does this scope represent a module body?  (SSS FIXME: what about script or eval script bodies?)
     */
    public boolean isModuleBody() {
        return false;
    }

    /**
     * Is this IRClassBody but not IRMetaClassBody?
     */
    public boolean isNonSingletonClassBody() {
        return false;
    }

    public boolean isFlipScope() {
        return true;
    }

    public boolean isTopLocalVariableScope() {
        return true;
    }

    /**
     * Is this an eval script or a regular file script?
     */
    public boolean isScriptScope() {
        return false;
    }

    public void savePersistenceInfo(int offset, IRReaderDecoder file) {
        instructionsOffsetInfoPersistenceBuffer = offset;
        persistenceStore = file;
    }
}
