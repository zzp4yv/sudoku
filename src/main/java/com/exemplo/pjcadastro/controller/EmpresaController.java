
package com.exemplo.pjcadastro.controller;

import com.exemplo.pjcadastro.model.Empresa;
import com.exemplo.pjcadastro.repository.EmpresaRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@Controller
@RequestMapping("/")
public class EmpresaController {

    private final EmpresaRepository repository;

    public EmpresaController(EmpresaRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public String listarEmpresas(Model model) {
        model.addAttribute("empresas", repository.findAll());
        return "index";
    }

    @GetMapping("/nova")
    public String novaEmpresa(Model model) {
        model.addAttribute("empresa", new Empresa());
        return "form";
    }

    @PostMapping("/salvar")
    public String salvarEmpresa(Empresa empresa) {
        repository.save(empresa);
        return "redirect:/";
    }

    @GetMapping("/editar/{cnpj}")
    public String editarEmpresa(@PathVariable String cnpj, Model model) {
        Optional<Empresa> empresa = repository.findById(cnpj);
        empresa.ifPresent(e -> model.addAttribute("empresa", e));
        return "form";
    }

    @GetMapping("/excluir/{cnpj}")
    public String excluirEmpresa(@PathVariable String cnpj) {
        repository.deleteById(cnpj);
        return "redirect:/";
    }
}
